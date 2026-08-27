package com.my_dream.server.crawler

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
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
 */
@Component
class HostRateLimiter(
    @param:Value("\${collector.request-delay-ms:1200}") private val delayMs: Long,
) {

    private val locks = ConcurrentHashMap<String, Any>()
    private val finishedAt = ConcurrentHashMap<String, Long>()

    fun <T> throttled(host: String, request: () -> T): T {
        val lock = locks.computeIfAbsent(host) { Any() }
        return synchronized(lock) {
            waitTurn(host)
            try {
                request()
            } finally {
                finishedAt[host] = System.nanoTime()
            }
        }
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
