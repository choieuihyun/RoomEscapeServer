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

    private val schedule = PollingSchedule(rangeDays = 7, farRangeDays = 7)

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
    fun `번호가 1씩 오른다는 전제 위에 서 있다`() {
        // ⚠️ 여기 있던 `바퀴 번호는 시계에서 나온다` 를 지웠다 (2026-09-01).
        // 그 테스트는 시각을 **정확히 +300_000ms** 밀고 번호가 1 오르는지 봤는데,
        // 300_000ms 는 설정값이지 실제 주기가 아니다 — 실제로는 470초쯤 지난다.
        // **운영에서 한 번도 일어나지 않는 상황을 재고 통과하고 있었다.**
        //
        // 번호를 만드는 일은 SweepTicker 로 옮겼고 (SweepTickerTest 가 1씩 오르는지 잰다),
        // 여기서는 **그 전제가 깨지면 순환이 어떻게 망가지는지**를 박아 둔다.
        // 이게 있어야 나중에 누가 번호 매기는 방식을 다시 건드릴 때 이유가 보인다.
        val 금요일 = LocalDate.of(2026, 8, 28)
        // **횟수가 아니라 간격을 잰다.** 8바퀴에 4번 나오는 것은 양쪽 다 같을 수 있다 —
        // 실제로 아팠던 것은 "몰려서 나오고 오래 안 나오는" 것이었다 (실측 ✗✗●●✗✗●●)
        fun 간격(번호: (Int) -> Long): Set<Int> =
            (0 until 8).filter { 금요일 in schedule.datesForSweep(번호(it), thursday) }
                .zipWithNext { a, b -> b - a }.toSet()

        assertEquals(setOf(2), 간격 { it.toLong() }, "1씩 오르면 금요일은 정확히 2바퀴마다다")

        // 시계에서 뽑던 옛 방식을 흉내낸다 — 470초를 300초로 나누니 1~2 씩 뛴다
        assertTrue(
            간격 { (it * 470L * 1000) / 300_000 }.size > 1,
            "번호가 1씩 오르지 않으면 간격이 들쭉날쭉해진다 — 이것이 2026-09-01 의 버그다",
        )
    }

    @Test
    fun `주말이 창 밖이어도 나머지는 돈다`() {
        // 창을 3일로 줄이면 주말이 없을 수 있다. 그래도 평일 순환은 계속돼야 한다
        val short = PollingSchedule(rangeDays = 3, farRangeDays = 3)
        val monday = LocalDate.of(2026, 8, 31)
        repeat(4) { n ->
            assertTrue(short.datesForSweep(n.toLong(), monday).isNotEmpty(), "$n 번째가 비었다")
        }
    }
}
