package com.my_dream.server.sync

import com.my_dream.server.crawler.play33.DaySchedule
import com.my_dream.server.crawler.play33.Play33Branch
import com.my_dream.server.crawler.play33.Play33Crawler
import com.my_dream.server.notify.NotificationService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * 수집을 **한다**. 언제 하는지는 [Play33CollectJob] 이 정한다.
 *
 * 둘을 나눈 이유: 스케줄을 끈 상태에서도 손으로 한 번 수집해 보는 길이 있어야 한다.
 * 한 클래스에 묶어 두면 스케줄러를 끄는 순간 수집 기능까지 같이 사라진다.
 */
@Component
class Play33Collector(
    private val crawler: Play33Crawler,
    private val sync: ScheduleSyncService,
    private val notifications: NotificationService,
    @param:Value("\${collector.play33.request-delay-ms:1200}") private val requestDelayMs: Long,
    @param:Value("\${collector.play33.store-concurrency:4}") private val storeConcurrency: Int,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 플레이33 전체를 한 바퀴. `4지점 × 7일 = 28요청`이면 전부라서
     * 감시 대상을 골라 긁을 필요가 없다 (아키텍처 D3).
     *
     * **매장 사이는 병렬, 매장 안은 순차다** (아키텍처 D13).
     * 수집 윤리 규칙은 "매장당 동시 1개, 초당 1회 미만" 이라 *매장 단위*로 걸린다 —
     * 다른 매장을 동시에 긁는 것은 어느 매장에도 부담을 더하지 않는다.
     *
     * 전부 순차로 돌면 한 바퀴가 `매장수 × 7 × 1.2초` 로 늘어나서,
     * 매장이 늘어날수록 수집 주기 자체가 지켜지지 않는다.
     */
    fun collectAll(): SweepSummary {
        val today = LocalDate.now()
        val branches = Play33Branch.entries
        val threads = storeConcurrency.coerceIn(1, branches.size)
        // 바퀴마다 만들고 접는다. 스케줄러를 꺼 두면 스레드도 남지 않는다
        val pool = Executors.newFixedThreadPool(threads) { r ->
            Thread(r, "play33-collect").apply { isDaemon = true }
        }
        val startedAt = System.nanoTime()

        return try {
            pool.invokeAll(branches.map { branch -> Callable { collectBranch(branch, today) } })
                .map { future ->
                    // 매장 하나가 통째로 실패해도 나머지 집계는 살린다
                    runCatching { future.get() }.getOrElse {
                        log.warn("지점 수집이 통째로 실패 — {}", it.message)
                        SweepSummary(0, 0, RESERVATION_RANGE_DAYS)
                    }
                }
                .fold(SweepSummary(0, 0, 0)) { a, b ->
                    SweepSummary(a.transitions + b.transitions, a.quarantined + b.quarantined, a.failures + b.failures)
                }
                .also {
                    val took = Duration.ofNanos(System.nanoTime() - startedAt)
                    log.info(
                        "수집 한 바퀴 완료 — {}초 ({}개 지점 동시), 전이 {}건, 격리 {}건, 실패 {}건",
                        took.toSeconds(), threads, it.transitions, it.quarantined, it.failures,
                    )
                }
        } finally {
            pool.shutdown()
        }
    }

    /** 한 지점의 7일치. **여기는 순차다** — 같은 매장에 동시 요청을 보내지 않는다. */
    private fun collectBranch(branch: Play33Branch, today: LocalDate): SweepSummary {
        var transitions = 0
        var quarantined = 0
        var failures = 0

        for (offset in 0 until RESERVATION_RANGE_DAYS) {
            val date = today.plusDays(offset.toLong())
            // 한 지점·하루가 실패해도 나머지는 계속 돈다
            try {
                val result = collectOne(branch, date)
                transitions += result.transitions.size
                quarantined += result.quarantined.size
            } catch (e: Exception) {
                failures++
                log.warn("수집 실패 — {} {} : {}", branch.branchName, date, e.message)
            }
            // 마지막 날짜 뒤에는 쉬지 않는다 — 다음 요청이 없는데 기다릴 이유가 없다
            if (offset < RESERVATION_RANGE_DAYS - 1) pauseBetweenRequests()
        }

        return SweepSummary(transitions, quarantined, failures)
    }

    fun collectOne(branch: Play33Branch, date: LocalDate): SyncResult {
        val day = crawler.fetch(branch, date)
        warnIfRangeChanged(day)
        warnIfNoThemes(day)

        val result = sync.sync(day)

        result.transitions.forEach {
            log.info("🎟️  자리 남 — {} {} {} {}", it.branchName, it.themeName, it.date, it.time)
        }
        // **저장이 끝난 뒤에** 알린다. 같은 트랜잭션 안에서 보내면 롤백돼도 알림은 못 되돌린다.
        // 알림 경로가 통째로 터져도 수집 한 바퀴는 계속 돌아야 한다 — 수집이 알림에 종속되면 안 된다
        runCatching { notifications.onTransitions(result.transitions) }
            .onFailure { log.error("알림 판정 실패 — 수집은 계속한다 : {}", it.message, it) }
        result.quarantined.forEach {
            val tail = if (it.recovered) {
                "${it.consecutive}회 연속이라 기준선 복구를 위해 이번엔 저장했다 (알림은 보내지 않음)"
            } else {
                "이번 수집분은 저장하지 않음 (${it.consecutive}회 연속)"
            }
            log.error(
                "⚠️  위생 검사 격리 — {} {} : 매진이던 {}개 중 {}개가 한 번에 풀렸다. 파서 확인 필요. {}",
                it.themeName, it.date, it.previouslyUnavailable, it.flipped, tail,
            )
        }
        return result
    }

    /**
     * 테마가 하나도 없는 지점은 **정상일 수도 있고**(지점 준비 중 · 테마를 내린 상태)
     * **파서가 깨진 신호일 수도 있다.** 조용히 0 으로 지나가면 둘을 구분할 길이 없다.
     *
     * 예외로 끊지는 않는다 — 실제로 비어 있는 지점이 있고(2026-08-26 기준 수원점),
     * 그때마다 수집 한 바퀴를 실패로 만들 이유는 없다. 대신 눈에 띄게 남긴다.
     */
    private fun warnIfNoThemes(day: DaySchedule) {
        if (day.themes.isEmpty()) {
            log.warn(
                "테마가 하나도 없다 — {} {}. 지점이 비어 있는 게 맞는지, 파서가 깨진 건지 확인이 필요하다",
                day.branch.branchName, day.date,
            )
        }
    }

    /** 사이트가 예약 오픈 범위를 바꾸면 우리 순회 범위도 바뀌어야 한다. 조용히 어긋나지 않게 알린다. */
    private fun warnIfRangeChanged(day: DaySchedule) {
        val reported = day.reservationRangeDays ?: return
        if (reported != RESERVATION_RANGE_DAYS) {
            log.warn(
                "예약 오픈 범위가 {}일로 바뀌었다 (코드 기준 {}일). 순회 범위를 조정해야 한다",
                reported, RESERVATION_RANGE_DAYS,
            )
        }
    }

    private fun pauseBetweenRequests() {
        try {
            Thread.sleep(requestDelayMs)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        /** 사이트가 `reservation_range_day` 로 밝힌 값. `[오늘, 오늘+6]` 이 열린다 */
        const val RESERVATION_RANGE_DAYS = 7
    }
}

data class SweepSummary(val transitions: Int, val quarantined: Int, val failures: Int)
