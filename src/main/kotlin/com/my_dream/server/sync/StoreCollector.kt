package com.my_dream.server.sync

import com.my_dream.server.crawler.FetchUnit
import com.my_dream.server.crawler.StoreAdapter
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
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
 */
@Component
class StoreCollector(
    private val adapters: List<StoreAdapter>,
    private val ingest: ScheduleIngest,
    private val schedule: PollingSchedule,
    @param:Value("\${collector.site-concurrency:16}") private val siteConcurrency: Int,
    @param:Value("\${spring.datasource.hikari.maximum-pool-size:10}") private val dbPoolSize: Int,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 두 설정이 어긋나면 **증상이 "조회 API 가 가끔 느리다" 로만 나타난다.** 수집 스레드가
     * 저장할 때 커넥션을 하나씩 쥐는데 풀이 그보다 작으면, 수집이 도는 동안 API 가 대기한다.
     * 로그 어디에도 원인이 안 남는 종류라 기동할 때 한 번 크게 말해 둔다.
     *
     * 기동을 막지는 않는다 — 느려질 뿐 틀린 값을 저장하지는 않기 때문이다.
     * (키 없는 FCM 은 조용히 안 가므로 그쪽은 막는다. 실패 방향이 다르다)
     */
    @PostConstruct
    fun warnIfPoolTooSmall() {
        if (dbPoolSize <= siteConcurrency) {
            log.warn(
                "커넥션 풀({})이 동시 사이트 수({})보다 크지 않다 — 수집이 도는 동안 조회 API 가 커넥션을 못 잡는다. " +
                    "DB_POOL_SIZE 를 올리거나 COLLECTOR_SITE_CONCURRENCY 를 내릴 것",
                dbPoolSize, siteConcurrency,
            )
        }
    }

    /** 이번 바퀴 몫만 긁는다. 어느 날짜인지는 [PollingSchedule] 이 정한다 (D14) */
    fun collectAll(): SweepSummary = collect(schedule.datesFor())

    fun collect(dates: List<LocalDate>): SweepSummary {
        // 같은 서버를 쓰는 어댑터끼리 묶는다. 브랜드가 달라도 호스트가 같으면 한 줄이다
        val byHost = adapters.groupBy { it.host }
            .mapValues { (_, group) -> group.flatMap { it.plan(dates) } }
        if (byHost.isEmpty()) return SweepSummary(0, 0, 0)

        val threads = siteConcurrency.coerceIn(1, byHost.size)
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
                    SweepSummary(a.transitions + b.transitions, a.quarantined + b.quarantined, a.failures + b.failures)
                }
                .also {
                    val took = Duration.ofNanos(System.nanoTime() - startedAt)
                    log.info(
                        "수집 한 바퀴 완료 — {}초 (날짜 {} · 사이트 {}곳 · 요청 {}건 · 동시 {}), 전이 {}건, 격리 {}건, 실패 {}건",
                        took.toSeconds(), dates, byHost.size, byHost.values.sumOf { u -> u.size },
                        threads, it.transitions, it.quarantined, it.failures,
                    )
                }
        } finally {
            pool.shutdown()
        }
    }

    /** 한 사이트가 맡은 요청 전부. **여기는 끝까지 순차다** — 같은 서버에 동시에 보내지 않는다. */
    private fun runSite(host: String, units: List<FetchUnit>): SweepSummary {
        var transitions = 0
        var quarantined = 0
        var failures = 0

        for (unit in units) {
            // 요청 하나가 실패해도 나머지는 계속 돈다
            try {
                val result = ingest.ingest(unit.fetch())
                transitions += result.transitions.size
                quarantined += result.quarantined.size
            } catch (e: Exception) {
                failures++
                log.warn("수집 실패 — {} : {}", unit.label, e.message)
            }
        }

        log.debug("{} — {}요청 완료", host, units.size)
        return SweepSummary(transitions, quarantined, failures)
    }

    companion object {
        /** 사이트가 `reservation_range_day` 로 밝힌 값. `[오늘, 오늘+6]` 이 열린다 */
    }
}

data class SweepSummary(val transitions: Int, val quarantined: Int, val failures: Int)
