package com.my_dream.server.sync

import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 어느 날짜를 얼마나 자주 보는가 (아키텍처 D14).
 *
 * 우선순위는 **주말(압도적) > 금요일 > 나머지 평일** 이다.
 */
class PollingScheduleTest {

    private val schedule = PollingSchedule(rangeDays = 7, intervalMs = 300_000)

    /** 2026-08-27 은 목요일. 창은 목금토일월화수 */
    private val thursday = LocalDate.of(2026, 8, 27)

    private fun sweep(n: Long) = schedule.datesForSweep(n, thursday)

    @Test
    fun `주말은 매 바퀴 본다`() {
        repeat(8) { n ->
            val picked = sweep(n.toLong())
            val weekend = picked.filter { it.dayOfWeek in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) }
            assertEquals(2, weekend.size, "$n 번째 바퀴에 주말이 빠졌다: $picked")
        }
    }

    @Test
    fun `금요일은 두 바퀴에 한 번 본다`() {
        val seen = (0L until 8L).map { n -> sweep(n).any { it.dayOfWeek == DayOfWeek.FRIDAY } }
        assertEquals(listOf(true, false, true, false, true, false, true, false), seen)
    }

    @Test
    fun `평일은 한 바퀴에 하나씩 돌아가며 본다`() {
        // 목·월·화·수 네 개가 순환한다
        val weekdays = (0L until 4L).map { n ->
            sweep(n).single { it.dayOfWeek !in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY, DayOfWeek.FRIDAY) }
        }
        assertEquals(4, weekdays.toSet().size, "네 바퀴 안에 평일 넷이 전부 나와야 한다: $weekdays")
    }

    @Test
    fun `굶는 날짜가 없다`() {
        // 창 안의 모든 날짜가 8바퀴(40분) 안에 최소 한 번은 나와야 한다
        val covered = (0L until 8L).flatMap { sweep(it) }.toSet()
        val window = (0 until 7).map { thursday.plusDays(it.toLong()) }
        assertEquals(window.toSet(), covered)
    }

    @Test
    fun `한 바퀴가 3일 또는 4일치다`() {
        repeat(8) { n ->
            val size = sweep(n.toLong()).size
            assertTrue(size in 3..4, "$n 번째 바퀴가 ${size}일치다")
        }
        // 평균 3.5 — 전량(7일)의 절반이다
        val avg = (0L until 8L).sumOf { sweep(it).size } / 8.0
        assertEquals(3.5, avg)
    }

    @Test
    fun `바퀴 번호는 시계에서 나온다`() {
        // 카운터를 들고 있으면 재시작할 때마다 0 으로 돌아가 같은 평일만 계속 본다
        val a = schedule.sweepIndex(java.time.Instant.ofEpochMilli(1_800_000))
        val b = schedule.sweepIndex(java.time.Instant.ofEpochMilli(1_800_000 + 300_000))
        assertEquals(a + 1, b)
    }

    @Test
    fun `주말이 창 밖이어도 나머지는 돈다`() {
        // 창을 3일로 줄이면 주말이 없을 수 있다. 그래도 평일 순환은 계속돼야 한다
        val short = PollingSchedule(rangeDays = 3, intervalMs = 300_000)
        val monday = LocalDate.of(2026, 8, 31)
        repeat(4) { n ->
            assertTrue(short.datesForSweep(n.toLong(), monday).isNotEmpty(), "$n 번째가 비었다")
        }
    }
}
