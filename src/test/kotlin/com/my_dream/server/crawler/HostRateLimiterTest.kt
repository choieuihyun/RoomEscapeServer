package com.my_dream.server.crawler

import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 수집 속도 규칙이 실제로 걸리는 자리 (아키텍처 D13·D15).
 *
 * **재는 값은 "호스트당 요청 간격" 하나다.** 한 바퀴가 몇 초에 끝나는지가 아니다 —
 * 빨라지는 건 얼마든지 만들 수 있고, 그게 곧 규칙 위반이다.
 */
class HostRateLimiterTest {

    private val delayMs = 60L
    private val limiter = HostRateLimiter(delayMs)

    /** 요청이 실제로 나간 시각. 밀리초 */
    private fun record(into: MutableList<Pair<String, Long>>, host: String) =
        limiter.throttled(host) {
            synchronized(into) { into += host to System.nanoTime() / 1_000_000 }
        }

    private fun gaps(hits: List<Pair<String, Long>>, host: String) =
        hits.filter { it.first == host }.map { it.second }.sorted().zipWithNext { a, b -> b - a }

    @Test
    fun `같은 호스트로 가는 요청은 간격을 지킨다`() {
        val hits = mutableListOf<Pair<String, Long>>()
        repeat(5) { record(hits, "play33.kr") }

        val g = gaps(hits, "play33.kr")
        assertEquals(4, g.size)
        assertTrue(g.all { it >= delayMs - 10 }, "간격: $g")
    }

    @Test
    fun `여러 스레드가 동시에 불러도 한 줄로 선다`() {
        val hits = mutableListOf<Pair<String, Long>>()
        val pool = Executors.newFixedThreadPool(8)

        repeat(8) { pool.submit { record(hits, "play33.kr") } }
        pool.shutdown()
        pool.awaitTermination(30, TimeUnit.SECONDS)

        // 동시에 8개를 던져도 순서대로 나가야 한다. 겹치면 그 서버에 초당 여러 번이 간다
        val g = gaps(hits, "play33.kr")
        assertEquals(8, hits.size)
        assertTrue(g.all { it >= delayMs - 10 }, "간격: $g")
    }

    @Test
    fun `호스트가 다르면 서로 기다리지 않는다`() {
        val hits = mutableListOf<Pair<String, Long>>()
        val pool = Executors.newFixedThreadPool(2)
        val startedAt = System.nanoTime()

        repeat(4) { pool.submit { record(hits, "play33.kr") } }
        repeat(4) { pool.submit { record(hits, "www.keyescape.com") } }
        pool.shutdown()
        pool.awaitTermination(30, TimeUnit.SECONDS)

        val tookMs = (System.nanoTime() - startedAt) / 1_000_000
        // 순차라면 8칸(≈420ms), 병렬이면 4칸(≈180ms) 이다
        assertTrue(tookMs < delayMs * 7, "${tookMs}ms — 다른 호스트끼리 줄을 서고 있다")
        assertTrue(gaps(hits, "play33.kr").all { it >= delayMs - 10 })
        assertTrue(gaps(hits, "www.keyescape.com").all { it >= delayMs - 10 })
    }

    @Test
    fun `한 작업이 요청을 여러 번 해도 요청마다 걸린다`() {
        val hits = mutableListOf<Pair<String, Long>>()

        // 키이스케이프처럼 "작업 하나 = 테마 하나" 가 아니라 여러 번 요청하게 되는 경우.
        // 수집기에서 sleep 으로 조절했다면 이 3번이 한꺼번에 나갔을 것이다 —
        // 예전 테스트는 fetch() 호출만 세서 이걸 통과시켰다 (아키텍처 D15)
        repeat(2) { repeat(3) { record(hits, "www.keyescape.com") } }

        val g = gaps(hits, "www.keyescape.com")
        assertEquals(6, hits.size)
        assertTrue(g.all { it >= delayMs - 10 }, "간격: $g — 작업 안에서 몰아쳤다")
    }
}
