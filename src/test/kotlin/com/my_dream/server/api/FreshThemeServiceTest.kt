package com.my_dream.server.api

import com.my_dream.server.club.ClubError
import com.my_dream.server.club.ClubEventService
import com.my_dream.server.club.EventInput
import com.my_dream.server.club.PlayedInput
import com.my_dream.server.club.PlayedService
import com.my_dream.server.crawler.DaySchedule
import com.my_dream.server.crawler.HostRateLimiter
import com.my_dream.server.crawler.Slot
import com.my_dream.server.crawler.ThemeSchedule
import com.my_dream.server.crawler.play33.Play33Adapter
import com.my_dream.server.crawler.play33.Play33Branch
import com.my_dream.server.crawler.play33.Play33Client
import com.my_dream.server.crawler.play33.Play33Crawler
import com.my_dream.server.crawler.play33.Play33Parser
import com.my_dream.server.domain.ClubMember
import com.my_dream.server.domain.ClubMemberRepository
import com.my_dream.server.domain.MemberRole
import com.my_dream.server.domain.MemberStatus
import com.my_dream.server.sync.ScheduleSyncService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 협의 도우미 · 해 본 테마 고르기 목록 (§4.9 · D33). 지원 지점 목록이 어댑터에 있어 플레이33 4지점을 같이 띄운다
 * (실제 요청은 나가지 않는다)
 */
@DataJpaTest
@Import(
    FreshThemeService::class, ScheduleQueryService::class, ScheduleSyncService::class,
    Play33Adapter::class, Play33Crawler::class, Play33Client::class, Play33Parser::class, HostRateLimiter::class,
    ClubEventService::class, PlayedService::class,
)
class FreshThemeServiceTest @Autowired constructor(
    private val fresh: FreshThemeService,
    private val query: ScheduleQueryService,
    private val sync: ScheduleSyncService,
    private val events: ClubEventService,
    private val played: PlayedService,
    private val members: ClubMemberRepository,
) {

    private val date = LocalDate.of(2026, 11, 7)

    @BeforeEach
    fun setUp() {
        listOf("나", "너").forEach { members.save(ClubMember(it, "닉$it", MemberRole.MEMBER, MemberStatus.APPROVED, Instant.now())) }
        members.save(ClubMember("대기", "대기", MemberRole.MEMBER, MemberStatus.PENDING, Instant.now()))
    }

    private fun day(branch: Play33Branch, on: LocalDate = date, vararg themes: Pair<String, String>) = DaySchedule(
        store = branch.toStoreRef(),
        date = on,
        reservationRangeDays = 7,
        openDays = 7,
        themes = themes.map { (id, name) ->
            ThemeSchedule(id, name, null, null, "2~4인", 70, null, null, listOf(Slot(LocalTime.of(10, 0), true), Slot(LocalTime.of(19, 30), false)))
        },
    )

    private fun event(date: String? = this.date.toString()) =
        events.create("나", EventInput("모임", date, null, null, null, null, false, null, emptyList(), null, if (date == null) listOf("2026-11-07") else null)).id

    @Test
    fun `안 해 본 테마가 먼저 오고, 해 본 사람과 회차 id 가 같이 온다`() {
        sync.sync(day(Play33Branch.KONKUK, date, "18" to "목격자", "19" to "비밀의 화원"))
        sync.sync(day(Play33Branch.HONGDAE, date, "3" to "목격자"))
        val id = event()
        events.join("너", id)
        played.add("너", PlayedInput(themeName = "목격자", themeKey = "play33-konkuk:18"))
        played.add("나", PlayedInput(themeName = "비밀의화원"))

        val r = fresh.fresh("나", id, null)

        assertEquals(date, r.date)
        assertTrue(r.collected)
        assertNotNull(r.checkedAt)
        assertEquals(listOf("play33-hongdae:3", "play33-konkuk:18", "play33-konkuk:19"), r.themes.map { it.themeKey })
        assertEquals(listOf(emptyList(), listOf("너"), listOf("나")), r.themes.map { it.playedBy }, "홍대 목격자는 키가 달라 다른 테마")
        val first = r.themes.first()
        assertEquals("플레이33", first.storeName)
        assertEquals("홍대점", first.branchName)
        assertEquals(listOf(600, 1170), first.sessions.map { it.t })
        assertTrue(first.sessions.all { it.id > 0 }, "회차 id 로 바로 빈자리 알림을 건다")
        assertEquals(listOf("대전점", "수원점"), r.missing.map { it.branch }, "그날 회차가 없는 지원 지점")
        assertTrue(r.noRecord.isEmpty())
    }

    @Test
    fun `그날 모은 시간표가 없으면 collected 가 false — 빈 목록이 "열린 테마 없음" 이 아니다`() {
        val id = event()
        val r = fresh.fresh("나", id, "2026-12-25")
        assertFalse(r.collected)
        assertNull(r.checkedAt)
        assertTrue(r.themes.isEmpty())
        assertEquals(4, r.missing.size)
        assertEquals(listOf("나"), r.noRecord, "기록이 하나도 없는 참여자 — 그 사람의 '안 해 봄' 은 믿을 수 없다")
    }

    @Test
    fun `날짜 미정이면 date 를 줘야 하고, 틀린 날짜·없는 일정·승인 안 된 사람은 막힌다`() {
        val undecided = event(date = null)
        fun code(block: () -> Unit) = assertFailsWith<ClubError> { block() }.code
        assertEquals("BAD_RANGE", code { fresh.fresh("나", undecided, null) })
        assertEquals(LocalDate.of(2026, 11, 7), fresh.fresh("나", undecided, "2026-11-07").date)
        assertEquals("BAD_RANGE", code { fresh.fresh("나", undecided, "내일") })
        assertEquals("NO_SUCH_EVENT", code { fresh.fresh("나", 999_999, "2026-11-07") })
        assertEquals("NOT_CLUB_MEMBER", code { fresh.fresh("대기", undecided, "2026-11-07") })
    }

    @Test
    fun `테마 고르기 목록은 지원 지점의 테마 전부 — 지난 날짜에만 있던 테마도`() {
        sync.sync(day(Play33Branch.KONKUK, LocalDate.of(2020, 1, 1), "1" to "사라진 테마"))
        sync.sync(day(Play33Branch.KONKUK, date, "18" to "목격자"))
        sync.sync(day(Play33Branch.HONGDAE, date, "3" to "가나다"))

        val themes = query.themes()
        assertEquals(listOf("건대점", "건대점", "홍대점"), themes.map { it.branchName })
        assertEquals(listOf("play33-konkuk:18", "play33-konkuk:1", "play33-hongdae:3"), themes.map { it.themeKey }, "지점 → 이름 가나다순")
        assertEquals("플레이33", themes.first().storeName)
    }
}
