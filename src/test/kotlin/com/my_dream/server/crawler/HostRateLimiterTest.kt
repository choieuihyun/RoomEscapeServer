package com.my_dream.server.crawler

import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

    @Test
    fun `마지막 요청이 걸린 시간은 줄 선 시간을 빼고 잰다`() {
        // 감시 빠른 확인이 이 값으로 "상대가 느리다" 를 판단한다 (D27).
        // 우리가 간격을 지키느라 기다린 시간이 섞이면 멀쩡한 서버를 느리다고 읽는다
        val slow = HostRateLimiter(200)
        assertNull(slow.lastRequestMs("play33.kr"), "한 번도 안 보냈으면 값이 없다")

        slow.throttled("play33.kr") { }
        slow.throttled("play33.kr") { Thread.sleep(30) }

        val took = requireNotNull(slow.lastRequestMs("play33.kr"))
        assertTrue(took in 25..150, "대기 200ms 가 섞였다: ${took}ms")
        assertNull(slow.lastRequestMs("keyescape.com"), "호스트마다 따로 적는다")
    }

    // ── "그만 보내라" (D39) ─────────────────────────────────────────────

    /** 시계를 손으로 돌린다 — 30분 멈춤을 진짜로 기다리지 않는다 */
    private class Hands(var now: Instant = Instant.parse("2026-10-21T02:00:00Z")) {
        fun clock(): Clock = Clock.fixed(now, ZoneOffset.UTC)
    }

    private fun backoff(hands: Hands) = HostRateLimiter(0, backoffMinutes = 30, backoffMaxMinutes = 240).also { it.clock = hands.clock() }

    private fun HostRateLimiter.tick(hands: Hands, by: Duration) {
        hands.now = hands.now.plus(by)
        clock = hands.clock()
    }

    private fun tooMany(retryAfter: String? = null) = HttpClientErrorException.create(
        HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests",
        HttpHeaders().apply { if (retryAfter != null) set("Retry-After", retryAfter) }, ByteArray(0), null,
    )

    @Test
    fun `429 를 받으면 그 호스트로는 요청 자체가 안 나간다`() {
        val hands = Hands()
        val limiter = backoff(hands)
        assertFailsWith<HttpClientErrorException> { limiter.throttled("a.example") { throw tooMany() } }

        var sent = 0
        val paused = assertFailsWith<HostPausedException> { limiter.throttled("a.example") { sent++ } }
        assertEquals(0, sent, "쉬는 중인데 요청이 나갔다")
        assertEquals(hands.now.plus(Duration.ofMinutes(30)), paused.until)

        // 다른 호스트는 상관없다
        limiter.throttled("b.example") { sent++ }
        assertEquals(1, sent)
    }

    @Test
    fun `503 · 403 도 멈춘다 — 그 밖의 오류는 안 멈춘다`() {
        val limiter = backoff(Hands())
        runCatching { limiter.throttled("busy.example") { throw HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE) } }
        runCatching { limiter.throttled("waf.example") { throw HttpClientErrorException(HttpStatus.FORBIDDEN) } }
        // 404·500 은 "그만" 이 아니다 — 창 밖 날짜나 상대 버그일 수 있다. 멈추면 멀쩡한 날짜까지 못 본다
        runCatching { limiter.throttled("missing.example") { throw HttpClientErrorException(HttpStatus.NOT_FOUND) } }
        runCatching { limiter.throttled("buggy.example") { throw HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR) } }

        assertNotNull(limiter.pausedUntil("busy.example"))
        assertNotNull(limiter.pausedUntil("waf.example"))
        assertNull(limiter.pausedUntil("missing.example"))
        assertNull(limiter.pausedUntil("buggy.example"))
    }

    @Test
    fun `크롤러가 감싸서 던져도 상태 코드를 찾는다`() {
        val limiter = backoff(Hands())
        runCatching { limiter.throttled("a.example") { throw IllegalStateException("파싱 전에 실패", tooMany()) } }
        assertNotNull(limiter.pausedUntil("a.example"))
    }

    @Test
    fun `시간이 지나면 풀리고, 또 걸리면 두 배를 쉰다`() {
        val hands = Hands()
        val limiter = backoff(hands)
        runCatching { limiter.throttled("a.example") { throw tooMany() } }

        limiter.tick(hands, Duration.ofMinutes(31))
        assertNull(limiter.pausedUntil("a.example"), "30분이 지났는데 안 풀렸다")

        // 풀리자마자 또 429 — 이번엔 60분
        runCatching { limiter.throttled("a.example") { throw tooMany() } }
        assertEquals(hands.now.plus(Duration.ofMinutes(60)), limiter.pausedUntil("a.example"))
    }

    @Test
    fun `두 배씩 늘어도 상한에서 멈춘다`() {
        val hands = Hands()
        val limiter = backoff(hands)
        repeat(6) {
            runCatching { limiter.throttled("a.example") { throw tooMany() } }
            limiter.tick(hands, Duration.ofHours(5))
        }
        runCatching { limiter.throttled("a.example") { throw tooMany() } }
        assertEquals(hands.now.plus(Duration.ofMinutes(240)), limiter.pausedUntil("a.example"))
    }

    @Test
    fun `한 번 성공하면 다시 처음 길이부터 센다`() {
        val hands = Hands()
        val limiter = backoff(hands)
        runCatching { limiter.throttled("a.example") { throw tooMany() } }
        limiter.tick(hands, Duration.ofMinutes(31))
        limiter.throttled("a.example") { }

        runCatching { limiter.throttled("a.example") { throw tooMany() } }
        assertEquals(hands.now.plus(Duration.ofMinutes(30)), limiter.pausedUntil("a.example"), "성공 뒤인데 두 배로 쉬었다")
    }

    @Test
    fun `Retry-After 가 더 길면 그걸 따른다`() {
        val hands = Hands()
        val limiter = backoff(hands)
        runCatching { limiter.throttled("a.example") { throw tooMany(retryAfter = "7200") } }
        assertEquals(hands.now.plus(Duration.ofHours(2)), limiter.pausedUntil("a.example"))

        // 더 짧으면 우리 기본값 — 상대가 "10초 뒤" 라고 해도 30분은 쉰다
        runCatching { limiter.throttled("b.example") { throw tooMany(retryAfter = "10") } }
        assertEquals(hands.now.plus(Duration.ofMinutes(30)), limiter.pausedUntil("b.example"))
    }

    @Test
    fun `쉬는 중이라 안 보낸 요청은 간격 기록을 건드리지 않는다`() {
        val hands = Hands()
        val limiter = backoff(hands)
        runCatching { limiter.throttled("a.example") { Thread.sleep(20); throw tooMany() } }
        val before = limiter.lastRequestMs("a.example")

        runCatching { limiter.throttled("a.example") { } }
        assertEquals(before, limiter.lastRequestMs("a.example"), "보내지도 않은 요청이 응답 시간으로 적혔다")
    }
}
