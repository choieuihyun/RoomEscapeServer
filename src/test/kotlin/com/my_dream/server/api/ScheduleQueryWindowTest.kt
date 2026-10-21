package com.my_dream.server.api

import com.my_dream.server.crawler.DaySchedule
import com.my_dream.server.crawler.HostRateLimiter
import com.my_dream.server.crawler.Slot
import com.my_dream.server.crawler.ThemeSchedule
import com.my_dream.server.crawler.diaegg.DiaeggAdapter
import com.my_dream.server.crawler.diaegg.DiaeggBranch
import com.my_dream.server.crawler.diaegg.DiaeggClient
import com.my_dream.server.crawler.diaegg.DiaeggCrawler
import com.my_dream.server.crawler.diaegg.DiaeggParser
import com.my_dream.server.sync.ScheduleSyncService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 창이 오늘부터 시작하지 않는 매장(다이아에그 · 오늘+4 부터) — 창에서 빠진 앞쪽 날짜를 내놓지 않는다 (아키텍처 D42).
 *
 * 2026-10-21 운영: 10-21~10-24 가 10-17~10-20 에 마지막으로 본 값 그대로 남아 "가능" 으로 떴다.
 * 수집이 그 날짜를 더는 묻지 않으니 **고쳐질 일이 없는 값**이다.
 */
@DataJpaTest
@Import(
    ScheduleQueryService::class, ScheduleSyncService::class,
    DiaeggAdapter::class, DiaeggCrawler::class, DiaeggClient::class, DiaeggParser::class,
    HostRateLimiter::class,
)
class ScheduleQueryWindowTest @Autowired constructor(
    private val query: ScheduleQueryService,
    private val sync: ScheduleSyncService,
) {

    private val today = LocalDate.of(2026, 10, 21)
    private val key = DiaeggBranch.DIAEGG.key

    @Test
    fun `창 앞쪽에 남은 옛 날짜는 날짜 선택지에서 뺀다`() {
        // 나흘 전에 +4 로 받아 둔 10-21 과, 오늘 창 안인 10-25(+4)
        sync.sync(day(today))
        sync.sync(day(today.plusDays(4)))

        val branch = query.branches(today = today).single { it.id == key }
        assertEquals(listOf(today.plusDays(4)), branch.dates)
        assertNotNull(branch.checkedAt, "checkedAt 은 창 안 첫 날짜 기준이다")
    }

    @Test
    fun `창 앞쪽 날짜의 시간표는 없는 것으로 답한다`() {
        sync.sync(day(today.plusDays(3)))
        sync.sync(day(today.plusDays(4)))

        assertNull(query.schedule(key, today.plusDays(3), today = today), "+3 은 매장이 안 여는 자리 — 옛 '가능' 을 주면 헛걸음이다")
        assertNotNull(query.schedule(key, today.plusDays(4), today = today))
    }

    @Test
    fun `지난 날짜와 다른 매장은 그대로다`() {
        sync.sync(day(today.minusDays(1)))
        assertNotNull(query.schedule(key, today.minusDays(1), today = today), "지난 날짜는 원래 주던 대로")
        assertEquals(false, query.isBeforeWindow("play33-konkuk", today, today), "기본 창은 오늘부터")
    }

    private fun day(on: LocalDate) = DaySchedule(
        store = DiaeggBranch.DIAEGG.toStoreRef(),
        date = on,
        reservationRangeDays = 7,
        openDays = 7,
        themes = listOf(
            ThemeSchedule(
                externalId = "1", themeName = "테마", posterUrl = null,
                genre = null, capacity = null, runningMinutes = null, horrorLevel = null, difficulty = null,
                slots = listOf(Slot(LocalTime.of(12, 0), available = true)),
            ),
        ),
    )
}
