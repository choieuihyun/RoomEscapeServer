package com.my_dream.server.crawler

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * 수집 속도 규칙을 **여기 한 곳에서** 지킨다 — 매장당 동시 요청 1개, 초당 1회 미만.
 *
 * **HTTP 요청이 실제로 나가는 자리에 둔다.** 수집기에서 `Thread.sleep` 으로 조절하면
 * 규칙이 구조가 아니라 관례가 된다. 어댑터가 한 작업 안에서 요청을 두 번 하는 순간
 * 조용히 두 배가 나가고, **테스트는 통과한다** — 아키텍처 D13 에서 한 번 겪은 실수다.
 *
 * 잠금을 요청 **전체**에 걸쳐 잡는다:
 * - 같은 호스트로 두 요청이 겹치지 않는다 (동시 1개)
 * - 이전 요청이 **끝난 뒤**부터 간격을 잰다 (초당 1회 미만)
 *
 * 호스트가 다르면 잠금도 다르므로 서로 기다리지 않는다.
 *
 * ### "그만 보내라" 도 여기서 지킨다 (아키텍처 D39)
 *
 * 상대가 [BACK_OFF] 상태 코드를 주면 그 호스트를 **한동안 아예 안 부른다.** 수집 윤리의
 * "`429`/`503` → 즉시 백오프" 다. 전체 바퀴와 감시 빠른 확인이 **같은 멈춤**을 본다 —
 * 둘 다 이 자리를 지나가기 때문이다. 수집기마다 따로 두면 한쪽이 멈춰도 다른 쪽이 계속 두드린다.
 *
 * ```
 * 처음        [backoffMinutes] (기본 30분)
 * 또 걸리면    두 배씩 — 상한 [backoffMaxMinutes] (기본 4시간)
 * 성공하면     다시 처음부터
 * Retry-After 그 값이 더 길면 그걸 따른다 (상대가 직접 말한 시간이다)
 * ```
 *
 * **메모리에만 둔다** — 재시작하면 잊는다. 최악이라도 요청 하나를 더 보내 보고 같은 이유로 다시 멈출 뿐이다
 * (감시 빠른 확인의 멈춤과 같은 판단 · D27).
 */
@Component
class HostRateLimiter(
    @param:Value("\${collector.request-delay-ms:1200}") private val delayMs: Long,
    @param:Value("\${collector.backoff-minutes:30}") private val backoffMinutes: Long = 30,
    @param:Value("\${collector.backoff-max-minutes:240}") private val backoffMaxMinutes: Long = 240,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val locks = ConcurrentHashMap<String, Any>()
    private val finishedAt = ConcurrentHashMap<String, Long>()
    private val lastTookMs = ConcurrentHashMap<String, Long>()

    /** 호스트별 "이때까지 안 부른다" */
    private val pausedUntil = ConcurrentHashMap<String, Instant>()

    /** 연속으로 몇 번 "그만" 을 들었나. 성공하면 지운다 */
    private val strikes = ConcurrentHashMap<String, Int>()

    /** 멈춤이 풀렸는지 보는 시계. 테스트가 바꿔 끼워 기다리지 않고 만료를 확인한다 */
    internal var clock: Clock = Clock.systemUTC()

    /**
     * @throws HostPausedException 상대가 "그만" 이라고 해서 쉬는 중이면 **요청을 보내지 않고** 던진다
     */
    fun <T> throttled(host: String, request: () -> T): T {
        val lock = locks.computeIfAbsent(host) { Any() }
        return synchronized(lock) {
            // 잠금을 잡은 **뒤에** 본다 — 줄 서 있는 동안 앞 요청이 멈춤을 걸었을 수 있다.
            // 보내지 않은 요청은 finishedAt·lastTookMs 를 건드리지 않는다 (간격 계산이 어긋나지 않게)
            pausedUntil[host]?.let { until -> if (until.isAfter(clock.instant())) throw HostPausedException(host, until) }
            waitTurn(host)
            val startedAt = System.nanoTime()
            try {
                request().also { strikes.remove(host) }
            } catch (e: Exception) {
                e.httpResponse()?.takeIf { it.statusCode.value() in BACK_OFF }?.let { pause(host, it.statusCode.value(), retryAfter(it.responseHeaders?.getFirst("Retry-After"))) }
                throw e
            } finally {
                val now = System.nanoTime()
                finishedAt[host] = now
                lastTookMs[host] = (now - startedAt) / 1_000_000
            }
        }
    }

    /** 지금 쉬는 중인가. 쉬는 중이 아니면 null */
    fun pausedUntil(host: String): Instant? = pausedUntil[host]?.takeIf { it.isAfter(clock.instant()) }

    private fun pause(host: String, status: Int, retryAfter: Duration?) {
        val n = strikes.merge(host, 1, Int::plus)!!
        val doubled = Duration.ofMinutes(minOf(backoffMinutes shl minOf(n - 1, 20), backoffMaxMinutes))
        val wait = if (retryAfter != null && retryAfter > doubled) minOf(retryAfter, MAX_RETRY_AFTER) else doubled
        val until = clock.instant().plus(wait)
        pausedUntil[host] = until
        log.warn(
            "{} 이(가) HTTP {} — {}분 동안 이 호스트로 아무것도 보내지 않는다 ({} 까지 · 연속 {}번째{})",
            host, status, wait.toMinutes(), until, n, if (retryAfter != null) " · Retry-After ${retryAfter.seconds}초" else "",
        )
    }

    /** `Retry-After: 120` (초) 만 읽는다. 날짜 형식은 무시하고 기본 멈춤을 쓴다 — 짧게 쉬는 쪽으로 틀리지 않는다 */
    private fun retryAfter(header: String?): Duration? =
        header?.trim()?.toLongOrNull()?.takeIf { it > 0 }?.let { Duration.ofSeconds(it) }

    /**
     * 이 호스트로 간 **마지막 요청이 걸린 시간**. 아직 한 번도 안 갔으면 `null`.
     *
     * **여기서 재는 이유:** 바깥에서 재면 줄 선 시간과 [waitTurn] 의 대기까지 섞여서,
     * 우리가 기다린 것을 상대 서버가 느린 것으로 읽게 된다. 잠금 안쪽, 요청만 감싼 구간이
     * 상대가 실제로 답하는 데 쓴 시간이다 — 감시 빠른 확인이 "힘들어 보이면 멈춘다" 를
     * 이 값으로 판단한다 (아키텍처 D27).
     */
    fun lastRequestMs(host: String): Long? = lastTookMs[host]

    companion object {
        /**
         * "그만" 으로 읽는 상태 코드. `429` 너무 많다 · `503` 감당 못 한다 — 수집 윤리에 적힌 둘.
         * **`403` 도 넣었다.** 포인트나인처럼 방화벽이 붙은 매장이 막을 때 쓸 법한 코드다 —
         * 막혔는데 계속 두드리면 우회와 다를 게 없다(CLAUDE.md "차단당하면 그때 대상에서 뺀다")
         */
        val BACK_OFF = setOf(403, 429, 503)

        /** 상대가 하루 넘게 쉬라고 해도 하루로 자른다 — 그 뒤에 한 번 더 물어보고 또 쉬면 된다 */
        private val MAX_RETRY_AFTER: Duration = Duration.ofHours(24)
    }

    private fun waitTurn(host: String) {
        val last = finishedAt[host] ?: return
        val elapsedMs = (System.nanoTime() - last) / 1_000_000
        val remaining = delayMs - elapsedMs
        if (remaining <= 0) return
        try {
            Thread.sleep(remaining)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}

/** 상대가 "그만" 이라고 해서 쉬는 중인 호스트로 요청하려 했다. **요청은 나가지 않았다** */
class HostPausedException(val host: String, val until: Instant) :
    RuntimeException("$host 쉬는 중 ($until 까지) — 요청을 보내지 않았다")
