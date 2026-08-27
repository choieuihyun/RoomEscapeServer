package com.my_dream.server.api

import com.my_dream.server.crawler.DaySchedule
import com.my_dream.server.crawler.play33.Play33Branch
import com.my_dream.server.crawler.Slot
import com.my_dream.server.crawler.ThemeSchedule
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
import kotlin.test.assertTrue

/**
 * 응답 모양이 Floduler 가 기대하는 형식과 맞는지. 계약이 어긋나면 프론트가 조용히 깨진다.
 * 계약 원본: Floduler `작업명세서.md` §4.4
 */
@DataJpaTest
@Import(ScheduleQueryService::class, ScheduleSyncService::class)
class ScheduleQueryServiceTest @Autowired constructor(
    private val query: ScheduleQueryService,
    private val sync: ScheduleSyncService,
) {

    private val date = LocalDate.of(2026, 9, 1)

    @Test
    fun `회차 시각을 자정부터의 분으로 준다`() {
        sync.sync(day())

        val theme = query.schedule("play33-konkuk", date)!!.themes.single()

        // 10:35 -> 635, 22:15 -> 1335. Floduler 의 session.t 가 이 형식이라 변환이 필요 없다
        assertEquals(listOf(635, 1335), theme.sessions.map { it.t })
    }

    @Test
    fun `예약 불가를 soldout 으로 뒤집어 준다`() {
        sync.sync(day())

        val sessions = query.schedule("play33-konkuk", date)!!.themes.single().sessions

        assertEquals(listOf(false, true), sessions.map { it.soldout })
    }

    @Test
    fun `소요시간은 홈페이지 값 그대로 준다`() {
        sync.sync(day())

        val theme = query.schedule("play33-konkuk", date)!!.themes.single()

        assertEquals(65, theme.dur, "회차 간격(75분)으로 보정하지 않는다")
        assertEquals("플레이33 건대점", theme.place)
    }

    @Test
    fun `수집되지 않은 날짜는 null 을 준다`() {
        sync.sync(day())

        assertNull(query.schedule("play33-konkuk", date.plusDays(1)))
    }

    @Test
    fun `모르는 지점은 null 을 준다`() {
        assertNull(query.schedule("does-not-exist", date))
    }

    @Test
    fun `지점 목록은 수집 여부와 무관하게 전부 준다`() {
        sync.sync(day())

        val branches = query.branches(today = date)

        assertEquals(4, branches.size)
        assertEquals(listOf("play33-konkuk", "play33-hongdae", "play33-daejeon", "play33-suwon"), branches.map { it.id })
        assertNull(branches.first { it.id == "play33-suwon" }.checkedAt, "수집 안 된 지점은 checkedAt 이 없다")
        assertTrue(branches.first { it.id == "play33-suwon" }.dates.isEmpty())
    }

    @Test
    fun `고를 수 있는 날짜를 알려준다`() {
        sync.sync(day())
        sync.sync(day(on = date.plusDays(2)))

        val konkuk = query.branches(today = date).first { it.id == "play33-konkuk" }

        assertEquals(listOf(date, date.plusDays(2)), konkuk.dates, "날짜 선택지를 그리려면 이게 필요하다")
    }

    @Test
    fun `지난 날짜는 선택지에서 빠진다`() {
        sync.sync(day(on = date.minusDays(3)))
        sync.sync(day())

        val konkuk = query.branches(today = date).first { it.id == "play33-konkuk" }

        assertEquals(listOf(date), konkuk.dates)
    }

    @Test
    fun `테마 고를 때 필요한 정보를 같이 준다`() {
        sync.sync(day())

        val theme = query.schedule("play33-konkuk", date)!!.themes.single()

        assertEquals("play33-konkuk:18", theme.id)
        assertEquals("드라마/스릴러", theme.genre)
        assertEquals("2~3인", theme.capacity)
        assertEquals(2, theme.minPeople)
        assertEquals(3, theme.maxPeople, "4명이면 이 테마를 못 간다는 걸 화면이 알 수 있어야 한다")
        assertEquals(1.0, theme.horrorLevel)
        assertEquals(2.0, theme.difficulty)
    }

    @Test
    fun `매진 회차도 빠짐없이 준다`() {
        sync.sync(day())

        val sessions = query.schedule("play33-konkuk", date)!!.themes.single().sessions

        assertEquals(2, sessions.size, "매진이라고 빼면 나중에 감시 버튼을 붙일 데가 없다")
        assertEquals(1, sessions.count { it.soldout })
    }

    @Test
    fun `응답에 언제 기준인지가 들어간다`() {
        sync.sync(day())

        assertNotNull(query.schedule("play33-konkuk", date)!!.checkedAt)
    }

    private fun day(on: LocalDate = date) = DaySchedule(
        store = Play33Branch.KONKUK.toStoreRef(),
        date = on,
        reservationRangeDays = 7,
        themes = listOf(
            ThemeSchedule(
                externalId = "18",
                themeName = "목격자",
                posterUrl = null,
                genre = "드라마/스릴러",
                capacity = "2~3인",
                runningMinutes = 65,
                horrorLevel = 1.0,
                difficulty = 2.0,
                slots = listOf(
                    Slot(LocalTime.of(10, 35), available = true),
                    Slot(LocalTime.of(22, 15), available = false),
                ),
            ),
        ),
    )
}
