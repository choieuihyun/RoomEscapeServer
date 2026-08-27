package com.my_dream.server.sync

import com.my_dream.server.crawler.DaySchedule
import com.my_dream.server.crawler.play33.Play33Branch
import com.my_dream.server.crawler.Slot
import com.my_dream.server.crawler.ThemeSchedule
import com.my_dream.server.domain.TimeSlotRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M2 의 심장 — 저장이 upsert 로 되는지, 그리고 `매진 → 가능` 전이가 잡히는지.
 */
@DataJpaTest
@Import(ScheduleSyncService::class)
class ScheduleSyncServiceTest @Autowired constructor(
    private val sync: ScheduleSyncService,
    private val slots: TimeSlotRepository,
) {

    private val date = LocalDate.of(2026, 9, 1)

    @Test
    fun `첫 수집은 전이가 없다`() {
        val result = sync.sync(day(soldOut = listOf(1, 2, 3, 4)))

        assertTrue(result.transitions.isEmpty(), "비교할 이전 상태가 없으므로 전이가 아니다")
        assertEquals(6, slots.count().toInt())
    }

    @Test
    fun `매진이 풀리면 전이로 잡힌다`() {
        sync.sync(day(soldOut = listOf(1, 2, 3, 4)))

        val result = sync.sync(day(soldOut = listOf(1, 2, 3)))

        assertEquals(1, result.transitions.size)
        assertEquals(TIMES[4], result.transitions.single().time)
        assertEquals("목격자", result.transitions.single().themeName)
        assertEquals("play33-konkuk", result.transitions.single().storeKey)
    }

    @Test
    fun `가능이 매진으로 바뀌는 것은 전이가 아니다`() {
        sync.sync(day(soldOut = listOf(1, 2, 3)))

        val result = sync.sync(day(soldOut = listOf(1, 2, 3, 4)))

        assertTrue(result.transitions.isEmpty())
    }

    @Test
    fun `같은 회차를 여러 번 수집해도 행이 늘지 않는다`() {
        sync.sync(day(soldOut = listOf(1, 2, 3, 4)))
        sync.sync(day(soldOut = listOf(1, 2)))
        sync.sync(day(soldOut = listOf(1, 2, 3, 4, 5)))

        assertEquals(6, slots.count().toInt(), "upsert 라 회차 수만큼만 있어야 한다")
        assertEquals(5, slots.findAll().count { !it.available })
    }

    @Test
    fun `매진이 한꺼번에 절반 넘게 풀리면 격리하고 저장하지 않는다`() {
        sync.sync(day(soldOut = listOf(0, 1, 2, 3, 4, 5)))

        // 파서가 깨져 전부 예약 가능으로 읽힌 상황
        val result = sync.sync(day(soldOut = emptyList()))

        assertTrue(result.transitions.isEmpty(), "격리된 수집분에서는 알림이 나가면 안 된다")
        assertEquals(1, result.quarantined.size)
        assertEquals(6, result.quarantined.single().previouslyUnavailable)
        assertEquals(6, result.quarantined.single().flipped)
        assertTrue(slots.findAll().all { !it.available }, "격리분은 저장되지 않아 기준선이 그대로여야 한다")
    }

    @Test
    fun `격리가 반복되면 기준선을 되살리되 알림은 내지 않는다`() {
        sync.sync(day(soldOut = ALL))

        // 같은 판정이 세 번 — 앞의 둘은 저장을 건너뛰고, 세 번째에 기준선을 되살린다
        val first = sync.sync(day(soldOut = emptyList()))
        val second = sync.sync(day(soldOut = emptyList()))
        val third = sync.sync(day(soldOut = emptyList()))

        assertEquals(listOf(1, 2, 3), listOf(first, second, third).map { it.quarantined.single().consecutive })
        assertEquals(listOf(false, false, true), listOf(first, second, third).map { it.quarantined.single().recovered })
        assertTrue(third.transitions.isEmpty(), "복구 저장분에서도 알림은 나가면 안 된다")
        assertTrue(slots.findAll().all { it.available }, "세 번째에는 저장돼 기준선이 최신이 된다")

        // 기준선이 되살아났으니 다음 수집은 정상으로 돌아온다
        assertTrue(sync.sync(day(soldOut = emptyList())).quarantined.isEmpty())
    }

    @Test
    fun `정상 수집이 끼면 연속 격리 카운트가 초기화된다`() {
        sync.sync(day(soldOut = ALL))
        assertEquals(1, sync.sync(day(soldOut = emptyList())).quarantined.single().consecutive)

        sync.sync(day(soldOut = ALL))                    // 정상 수집 (되돌아감 = 전이 아님)

        assertEquals(1, sync.sync(day(soldOut = emptyList())).quarantined.single().consecutive)
    }

    @Test
    fun `표본이 작으면 위생 검사에 걸리지 않는다`() {
        sync.sync(day(soldOut = listOf(0, 1)))

        val result = sync.sync(day(soldOut = emptyList()))

        assertTrue(result.quarantined.isEmpty(), "매진 2개는 정상적인 취소로 볼 만한 규모다")
        assertEquals(2, result.transitions.size)
    }

    @Test
    fun `테마 이름이 바뀌어도 같은 테마로 본다`() {
        sync.sync(day(soldOut = listOf(1), themeName = "목격자"))

        sync.sync(day(soldOut = listOf(1), themeName = "목격자 시즌2"))

        assertEquals(6, slots.count().toInt(), "externalId 가 같으면 행이 갈라지지 않는다")
    }

    // --- 헬퍼 ---

    /** [soldOut] 은 [TIMES] 의 인덱스. 나머지는 예약 가능으로 만든다. */
    private fun day(soldOut: List<Int>, themeName: String = "목격자") = DaySchedule(
        store = Play33Branch.KONKUK.toStoreRef(),
        date = date,
        reservationRangeDays = 7,
        themes = listOf(
            ThemeSchedule(
                externalId = "18",
                themeName = themeName,
                posterUrl = null,
                genre = "드라마/스릴러",
                capacity = "2~3인",
                runningMinutes = 65,
                horrorLevel = 1.0,
                difficulty = 2.0,
                slots = TIMES.mapIndexed { i, t -> Slot(t, available = i !in soldOut) },
            ),
        ),
    )

    companion object {
        private val ALL = (0..5).toList()
        private val TIMES = listOf(
            LocalTime.of(10, 35), LocalTime.of(11, 50), LocalTime.of(13, 10),
            LocalTime.of(14, 30), LocalTime.of(15, 45), LocalTime.of(17, 0),
        )
    }
}
