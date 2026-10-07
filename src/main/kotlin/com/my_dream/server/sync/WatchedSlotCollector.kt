package com.my_dream.server.sync

import com.my_dream.server.crawler.FetchUnit
import com.my_dream.server.crawler.HostRateLimiter
import com.my_dream.server.crawler.StoreAdapter
import com.my_dream.server.domain.WatchRepository
import com.my_dream.server.domain.isPast
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClientResponseException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap

/**
 * **감시가 걸린 자리만** 전체 바퀴보다 자주 본다 (아키텍처 D27). **언제** 도는지는 [WatchedSlotCollectJob] 이 정한다.
 *
 * 전체 바퀴([StoreCollector])는 그대로다. 여기는 그 사이사이에 "누가 기다리고 있는 자리" 만
 * 한 번 더 묻는 좁은 길이다. 취소표는 몇 분 안에 채가는데 전체 바퀴는 토·일도 약 7.6분,
 * 평일은 약 30분에 한 번이라(실측 2026-10-07), 기다리는 사람이 있는 자리만큼은 더 자주 본다.
 *
 * ### 남의 서버를 더 두드리는 길이라 스스로 묶어 둔다
 *
 * D3 은 "수집은 감시를 모른다 — 그래서 사용자가 늘어도 남의 서버 부하는 같다" 였다.
 * 이 클래스가 그 문장을 깬다. 대신 아래 넷으로 **늘어날 수 있는 양에 천장**을 둔다.
 *
 * ```
 * 감시가 없으면          요청 0건. 이 길은 아무것도 안 한다
 * 한 번에 최대 N건       [maxUnits]. 감시가 아무리 많아도 한 번에 이만큼만 — 오래 안 본 것부터
 * 새벽에는 쉰다          [quietFromHour]~[quietUntilHour]. 그 시간엔 전체 바퀴만 돈다
 * 힘들어 보이면 멈춘다    실패하거나 응답이 느리면 그 호스트는 [pauseMinutes] 동안 건너뛴다
 * ```
 *
 * 속도 규칙(호스트당 동시 1개 · 초당 1회 미만)은 여기서 따로 지키지 않는다 —
 * 요청이 나가는 자리의 [HostRateLimiter] 가 전체 바퀴와 **같은 줄**에 세운다.
 */
@Component
class WatchedSlotCollector(
    private val adapters: List<StoreAdapter>,
    private val ingest: ScheduleIngest,
    private val watches: WatchRepository,
    private val rateLimiter: HostRateLimiter,
    @param:Value("\${collector.watch.max-units:5}") private val maxUnits: Int,
    @param:Value("\${collector.watch.quiet-from-hour:1}") private val quietFromHour: Int,
    @param:Value("\${collector.watch.quiet-until-hour:7}") private val quietUntilHour: Int,
    @param:Value("\${collector.watch.pause-minutes:30}") private val pauseMinutes: Long,
    @param:Value("\${collector.watch.slow-ms:5000}") private val slowMs: Long,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 호스트별 "이때까지 건너뛴다". **메모리에만 둔다** — 재시작하면 잊는데, 그래도 된다.
     * 최악이라도 요청 하나를 더 보내 보고 같은 이유로 다시 멈출 뿐이다.
     * (알림 쿨다운을 DB 에 둔 것과 반대인 이유: 그쪽은 잊으면 알림이 다시 나간다)
     */
    private val pausedUntil = ConcurrentHashMap<String, LocalDateTime>()

    /** 마지막으로 로그에 알린 대상. **바뀔 때만** 다시 적는다 — 2분마다 같은 줄을 찍으면 로그가 그것으로 찬다 */
    private var announced: Set<String> = emptySet()
    private var unmatchedAnnounced: Set<Target> = emptySet()

    /** 확인해야 할 자리 하나. 같은 자리를 여럿이 감시해도 하나로 합친다 */
    private data class Target(
        val storeKey: String,
        val date: LocalDate,
        val themeExternalId: String,
        /** 마지막으로 확인한 시각. 한 번에 다 못 볼 때 **오래 안 본 것부터** 고르는 기준이다 */
        val staleSince: Instant,
    )

    private class Pick(val host: String, val unit: FetchUnit, val staleSince: Instant)

    fun check(now: LocalDateTime = LocalDateTime.now()): WatchCheckSummary {
        if (isQuiet(now.hour)) return WatchCheckSummary()

        val targets = targets(now)
        val picks = plan(targets, now)
        announce(picks.map { it.unit.label }.toSet())

        var requests = 0
        var transitions = 0
        var failures = 0

        for (pick in picks.take(maxUnits)) {
            // 바로 앞 요청에서 멈춤이 걸렸으면 같은 호스트의 남은 요청도 보내지 않는다
            if (isPaused(pick.host, now)) continue

            requests++
            val day = try {
                pick.unit.fetch()
            } catch (e: Exception) {
                failures++
                pause(pick.host, now, "${pick.unit.label} 요청 실패 (${describe(e)})")
                continue
            }

            // 답은 왔지만 느렸다. 받은 것은 저장하고, 다음부터 쉰다
            val took = rateLimiter.lastRequestMs(pick.host)
            if (took != null && took > slowMs) {
                pause(pick.host, now, "${pick.unit.label} 응답이 ${took}ms 걸렸다 (기준 ${slowMs}ms)")
            }

            // **저장 실패로는 멈추지 않는다.** 우리 DB 문제지 상대 서버가 힘들다는 신호가 아니다
            try {
                transitions += ingest.ingest(day).transitions.size
            } catch (e: Exception) {
                failures++
                log.warn("감시 빠른 확인 — 저장 실패 {} : {}", pick.unit.label, e.message)
            }
        }

        log.debug("감시 빠른 확인 — 요청 {}건, 전이 {}건, 실패 {}건", requests, transitions, failures)
        return WatchCheckSummary(requests, transitions, failures)
    }

    /**
     * 아직 매진이고, 아직 안 지났고, 누군가 기다리는 자리.
     *
     * "지난 자리" 는 [isPast] 로만 판정한다 — 쿼리는 날짜로 거칠게 거를 뿐이다.
     */
    private fun targets(now: LocalDateTime): List<Target> =
        watches.findWaitingFrom(now.toLocalDate())
            .map { it.timeSlot }
            .filterNot { it.isPast(now) }
            .groupBy { Triple(it.theme.store.storeKey, it.date, it.theme.externalId) }
            .map { (key, slots) -> Target(key.first, key.second, key.third, slots.minOf { it.lastCheckedAt }) }

    /**
     * 자리마다 "그걸 확인해 주는 요청" 을 찾는다. 오래 안 본 것이 앞에 온다.
     *
     * 지점 하나를 물으면 테마가 전부 오는 매장은 같은 지점·날짜의 감시가 몇 개든 요청 하나로 합쳐진다.
     * 테마마다 묻는 매장(키이스케이프 · 제로월드)은 **감시 걸린 테마만** 고른다 —
     * 여기서 테마를 안 가리면 자리 하나에 그 지점의 테마 수만큼 요청이 나간다.
     */
    private fun plan(targets: List<Target>, now: LocalDateTime): List<Pick> {
        val accounted = mutableSetOf<Target>()

        val picks = adapters.flatMap { adapter ->
            val keys = adapter.branches.mapTo(HashSet()) { it.key }
            val mine = targets.filter { it.storeKey in keys }
            if (mine.isEmpty()) return@flatMap emptyList()

            // 쉬는 호스트는 계획조차 세우지 않는다. `plan()` 이 테마 목록을 받으러 나갈 수 있어서다
            if (isPaused(adapter.host, now)) {
                accounted += mine
                return@flatMap emptyList()
            }

            adapter.plan(mine.map { it.date }.distinct()).mapNotNull { unit ->
                val hit = mine.filter { unit.covers(it.storeKey, it.date, it.themeExternalId) }
                if (hit.isEmpty()) return@mapNotNull null
                accounted += hit
                Pick(adapter.host, unit, hit.minOf { it.staleSince })
            }
        }

        warnUnmatched(targets.toSet() - accounted)
        return picks.sortedBy { it.staleSince }
    }

    /**
     * 감시는 있는데 그걸 확인해 줄 요청을 못 찾았다.
     *
     * 정상일 수도 있다(그 날짜가 지점의 예약 창 밖으로 나갔다 · 지점을 내렸다).
     * 그런데 **어댑터가 적은 테마 ID 와 DB 의 테마 ID 가 어긋난 경우에도 똑같이 보인다** —
     * 그때는 그 감시가 빠른 확인에서 영영 빠지는데 아무 증상이 없다. 그래서 소리를 낸다.
     * 전체 바퀴는 감시와 무관하게 돌므로 알림 자체가 끊기지는 않는다. 늦어질 뿐이다.
     */
    private fun warnUnmatched(unmatched: Set<Target>) {
        if (unmatched == unmatchedAnnounced) return
        unmatchedAnnounced = unmatched
        unmatched.forEach {
            log.warn(
                "감시 빠른 확인 — 맞는 요청을 못 찾았다: {} {} 테마 {}. 전체 바퀴로만 확인된다. " +
                    "예약 창 밖이 아니라면 FetchUnit.themeExternalId 가 DB 의 external_id 와 같은지 볼 것",
                it.storeKey, it.date, it.themeExternalId,
            )
        }
    }

    private fun announce(labels: Set<String>) {
        if (labels == announced) return
        announced = labels
        if (labels.isEmpty()) {
            log.info("감시 빠른 확인 — 대상 없음. 추가 요청을 보내지 않는다")
        } else {
            val capped = if (labels.size > maxUnits) " · 한 번에 ${maxUnits}건씩, 오래 안 본 것부터" else ""
            log.info("감시 빠른 확인 — 대상 {}건 {}{}", labels.size, labels, capped)
        }
    }

    private fun isPaused(host: String, now: LocalDateTime) = pausedUntil[host]?.isAfter(now) == true

    private fun pause(host: String, now: LocalDateTime, reason: String) {
        val until = now.plusMinutes(pauseMinutes)
        pausedUntil[host] = until
        log.warn(
            "감시 빠른 확인 — {} 을(를) {}분 쉰다 ({} 까지). {}. 전체 바퀴는 그대로 돈다",
            host, pauseMinutes, until.toLocalTime().withNano(0), reason,
        )
    }

    /** `429` · `503` 인지 로그만 보고 알 수 있게 상태 코드를 꺼낸다. 크롤러가 감싸서 던져도 찾는다 */
    private fun describe(e: Throwable): String {
        val http = generateSequence(e) { it.cause }.take(MAX_CAUSE_DEPTH)
            .filterIsInstance<RestClientResponseException>().firstOrNull()
        return if (http != null) "HTTP ${http.statusCode.value()}" else e.message ?: e.javaClass.simpleName
    }

    /**
     * 쉬는 시간인가. 끝 시각은 포함하지 않는다 — `1~7` 이면 01:00 부터 06:59 까지 쉰다.
     * 자정을 넘기는 설정(`23~6`)도 받는다. 두 값이 같으면 쉬는 시간이 없다.
     */
    private fun isQuiet(hour: Int): Boolean = when {
        quietFromHour == quietUntilHour -> false
        quietFromHour < quietUntilHour -> hour in quietFromHour until quietUntilHour
        else -> hour >= quietFromHour || hour < quietUntilHour
    }

    companion object {
        /** 예외 원인 사슬이 자기 자신을 가리키는 경우를 대비한 한도 */
        private const val MAX_CAUSE_DEPTH = 10
    }
}

data class WatchCheckSummary(val requests: Int = 0, val transitions: Int = 0, val failures: Int = 0)
