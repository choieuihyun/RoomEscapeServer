package com.my_dream.server.sync

import com.my_dream.server.crawler.FetchUnit
import com.my_dream.server.crawler.HostPausedException
import com.my_dream.server.crawler.StoreAdapter
import com.my_dream.server.crawler.describe
import com.my_dream.server.crawler.isUnreachable
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * 등록된 매장을 한 바퀴 돈다. **언제** 도는지는 [Play33CollectJob] 이 정한다.
 *
 * 둘을 나눈 이유: 스케줄을 끈 상태에서도 손으로 한 번 돌려 보는 길이 있어야 한다.
 * 한 클래스에 묶어 두면 스케줄러를 끄는 순간 수집 기능까지 같이 사라진다 (아키텍처 D9).
 *
 * **병렬 단위는 매장이 아니라 사이트(호스트)다** (D13). 어댑터가 서로 달라도 같은 서버를
 * 두드리면 한 줄로 선다. 속도는 `HostRateLimiter` 가 요청마다 직접 건다 —
 * 여기서 `sleep` 으로 조절하지 않는 이유는 그렇게 하면 규칙이 구조가 아니라 관례가 되기 때문이다.
 *
 * **스레드는 호스트 수만큼이다 — 상한이 없다** (D40 · D18 정정). 예전엔 `collector.site-concurrency=16` 으로
 * 묶었는데, 호스트가 그보다 많아지는 순간 바퀴가 파도로 쪼개져 **모든 매장이 같이 느려진다.**
 * 상한이 막던 것은 DB 커넥션이었고, 그건 이제 저장하는 자리([ScheduleIngest])가 직접 막는다.
 * 스레드는 대부분의 시간을 상대 응답과 1.2초 간격을 기다리며 보낸다 — 120개여도 CPU 를 거의 안 쓴다.
 */
@Component
class StoreCollector(
    private val adapters: List<StoreAdapter>,
    private val ingest: ScheduleIngest,
    private val schedule: PollingSchedule,
    private val ticker: SweepTicker,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 이번 바퀴 몫만 긁는다. **번호는 [SweepTicker] 가, 그 번호로 볼 날짜는 [PollingSchedule] 이** 정한다 (D14).
     *
     * 둘을 나눠 둔 이유는 2026-09-01 에 여기서 버그가 났기 때문이다 — 날짜 고르는 계산은
     * 멀쩡했는데 **번호가 1씩 오르지 않아서** 순환이 굶었다. 나눠 두면 각각을 따로 잴 수 있다.
     */
    fun collectAll(): SweepSummary = collect(schedule.datesForSweep(ticker.next()))

    fun collect(dates: List<LocalDate>): SweepSummary {
        // 같은 서버를 쓰는 어댑터끼리 묶는다. 브랜드가 달라도 호스트가 같으면 한 줄이다
        val byHost = adapters.groupBy { it.host }
            .mapValues { (_, group) -> group.flatMap { it.plan(dates) } }
        if (byHost.isEmpty()) return SweepSummary(0, 0, 0)

        // 호스트마다 한 줄. 속도 제한이 호스트별이라(D13) 더 많으면 할 일이 없고, 적으면 서로 기다리기만 한다.
        // **가상 스레드는 안 쓴다** — HostRateLimiter 가 synchronized 안에서 sleep 하는데, JDK 21 은 그 동안
        // 가상 스레드를 운반 스레드(코어 수만큼)에 묶어 버린다. 1 OCPU 서버에서 호스트 둘이 쉬면 나머지가 다 선다
        val threads = byHost.size
        // 바퀴마다 만들고 접는다. 스케줄러를 꺼 두면 스레드도 남지 않는다
        val pool = Executors.newFixedThreadPool(threads) { r ->
            Thread(r, "collect").apply { isDaemon = true }
        }
        val startedAt = System.nanoTime()

        return try {
            pool.invokeAll(byHost.map { (host, units) -> Callable { runSite(host, units) } })
                .map { future ->
                    // 사이트 하나가 통째로 실패해도 나머지 집계는 살린다
                    runCatching { future.get() }.getOrElse {
                        log.warn("사이트 수집이 통째로 실패 — {}", it.message)
                        SweepSummary(0, 0, 0)
                    }
                }
                .fold(SweepSummary(0, 0, 0)) { a, b ->
                    SweepSummary(
                        a.transitions + b.transitions, a.quarantined + b.quarantined, a.failures + b.failures,
                        a.skipped + b.skipped,
                    )
                }
                .also {
                    val took = Duration.ofNanos(System.nanoTime() - startedAt)
                    log.info(
                        "수집 한 바퀴 완료 — {}초 (날짜 {} · 사이트 {}곳 · 요청 {}건 · 동시 {}), 전이 {}건, 격리 {}건, 실패 {}건, 안 보냄 {}건",
                        took.toSeconds(), dates, byHost.size, byHost.values.sumOf { u -> u.size },
                        threads, it.transitions, it.quarantined, it.failures, it.skipped,
                    )
                }
        } finally {
            pool.shutdown()
        }
    }

    /**
     * 한 사이트가 맡은 요청 전부. **여기는 끝까지 순차다** — 같은 서버에 동시에 보내지 않는다.
     *
     * 요청 하나가 실패해도 나머지는 계속 돈다. **두 경우만 그 바퀴의 남은 요청을 접는다** (D39):
     *
     * ```
     * 상대가 "그만"(403·429·503) → HostRateLimiter 가 멈춤을 걸고, 다음 요청부터 안 나간다
     * 응답을 아예 못 받음(타임아웃·연결 거부) → 같은 호스트에 바로 다시 물어봐야 같은 결과다
     * ```
     *
     * 파싱·검증 실패로는 접지 않는다 — 그건 **우리 쪽 판정**이고, 같은 호스트의 다른 지점·날짜는
     * 멀쩡할 수 있다. 접은 요청은 저장도 안 하므로 DB 의 이전 값이 그대로 남는다(빈 값으로 덮지 않는다).
     */
    private fun runSite(host: String, units: List<FetchUnit>): SweepSummary {
        var transitions = 0
        var quarantined = 0
        var failures = 0

        for ((i, unit) in units.withIndex()) {
            try {
                val result = ingest.ingest(unit.fetch())
                transitions += result.transitions.size
                quarantined += result.quarantined.size
            } catch (e: HostPausedException) {
                log.warn("{} 쉬는 중 ({} 까지) — 이번 바퀴 남은 {}요청을 보내지 않는다", host, e.until, units.size - i)
                return SweepSummary(transitions, quarantined, failures, skipped = units.size - i)
            } catch (e: Exception) {
                failures++
                log.warn("수집 실패 — {} : {}", unit.label, e.describe())
                if (e.isUnreachable()) {
                    val left = units.size - i - 1
                    if (left > 0) log.warn("{} 응답을 못 받았다 — 이번 바퀴 남은 {}요청을 보내지 않는다", host, left)
                    return SweepSummary(transitions, quarantined, failures, skipped = left)
                }
            }
        }

        log.debug("{} — {}요청 완료", host, units.size)
        return SweepSummary(transitions, quarantined, failures)
    }

    companion object {
        /** 사이트가 `reservation_range_day` 로 밝힌 값. `[오늘, 오늘+6]` 이 열린다 */
    }
}

data class SweepSummary(
    val transitions: Int,
    val quarantined: Int,
    val failures: Int,
    /** 상대가 쉬라고 했거나 응답이 없어서 **보내지 않은** 요청. 실패와 따로 센다 — 보내지도 않은 것을 실패로 세면 고장처럼 보인다 */
    val skipped: Int = 0,
)
