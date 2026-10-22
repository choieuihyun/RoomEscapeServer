package com.my_dream.server.sync

import org.junit.jupiter.api.Test
import org.springframework.scheduling.support.SimpleTriggerContext
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** 감시 빠른 확인의 다음 시각 (아키텍처 D44) — 이전 확인이 끝난 뒤 5~7분 사이 무작위 */
class JitteredDelayTest {

    private val start = Instant.parse("2026-10-22T03:00:00Z")
    private val clock = Clock.fixed(start, ZoneOffset.UTC)
    private val min = Duration.ofMinutes(5).toMillis()
    private val max = Duration.ofMinutes(7).toMillis()

    private fun after(completed: Instant) = SimpleTriggerContext(completed.minusSeconds(3), completed.minusSeconds(3), completed)

    @Test
    fun `첫 실행은 등록 뒤 첫 지연만큼`() {
        val trigger = JitteredDelay(min, max, 60_000, clock = clock)
        assertEquals(start.plusSeconds(60), trigger.nextExecution(SimpleTriggerContext()))
    }

    @Test
    fun `다음은 이전이 끝난 뒤 5~7분 사이다 — 양 끝 포함`() {
        val done = start.plusSeconds(500)
        val low = JitteredDelay(min, max, 0, nextLong = { from, _ -> from }, clock = clock)
        val high = JitteredDelay(min, max, 0, nextLong = { _, until -> until - 1 }, clock = clock)
        assertEquals(done.plus(Duration.ofMinutes(5)), low.nextExecution(after(done)))
        assertEquals(done.plus(Duration.ofMinutes(7)), high.nextExecution(after(done)))
    }

    @Test
    fun `진짜 난수로 여러 번 뽑아도 범위를 벗어나지 않는다`() {
        val trigger = JitteredDelay(min, max, 0, clock = clock)
        val done = start.plusSeconds(500)
        val gaps = (1..500).map { Duration.between(done, trigger.nextExecution(after(done))).toMillis() }
        assertTrue(gaps.all { it in min..max }, "범위 밖: ${gaps.filterNot { it in min..max }.take(3)}")
        assertTrue(gaps.toSet().size > 1, "무작위가 아니다 — 늘 같은 값")
    }

    @Test
    fun `범위가 거꾸로면 뜨지 않는다`() {
        assertFailsWith<IllegalArgumentException> { JitteredDelay(max, min, 0) }
    }
}
