package com.my_dream.server.club

import com.my_dream.server.domain.ClubMember
import com.my_dream.server.domain.ClubMemberRepository
import com.my_dream.server.domain.MemberRole
import com.my_dream.server.domain.MemberStatus
import com.my_dream.server.domain.PlayedSource
import com.my_dream.server.domain.PlayedThemeRepository
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 해 본 테마 — 직접 기록 · 자동 기록 · 일정의 `playedBy` · 지난 일정 (§4.9 · D33).
 */
@DataJpaTest
@Import(PlayedService::class, PlayedRecorder::class, ClubEventService::class)
class PlayedServiceTest @Autowired constructor(
    private val service: PlayedService,
    private val recorder: PlayedRecorder,
    private val events: ClubEventService,
    private val members: ClubMemberRepository,
    private val played: PlayedThemeRepository,
    private val em: EntityManager,
    private val jdbc: JdbcTemplate,
) {

    private val today = LocalDate.of(2026, 11, 10)

    @BeforeEach
    fun setUp() {
        listOf("나", "너", "쟤").forEach { members.save(ClubMember(it, "닉$it", MemberRole.MEMBER, MemberStatus.APPROVED, Instant.now())) }
        members.save(ClubMember("대기", "대기", MemberRole.MEMBER, MemberStatus.PENDING, Instant.now()))
    }

    private fun code(block: () -> Unit) = assertFailsWith<ClubError> { block() }.code

    private fun event(
        date: String? = "2026-11-07",
        booked: Boolean = true,
        segs: List<SegmentInput> = listOf(seg("목격자", "play33-konkuk:18"), seg("비밀의 화원")),
    ) = events.create("나", EventInput("모임", date, null, null, null, null, booked, null, segs, null, if (date == null) listOf("2026-11-07") else null))

    private fun seg(name: String, key: String? = null) = SegmentInput(themeName = name, branchName = "건대점", themeKey = key, startMin = 600)

    // --- 직접 기록 ---

    @Test
    fun `직접 기록하고, 같은 테마를 또 보내면 있던 행을 그대로 준다`() {
        val first = service.add("나", PlayedInput(themeName = "목격자", themeKey = "play33-konkuk:18", playedOn = "2026-10-01"))
        val again = service.add("나", PlayedInput(themeName = "목 격 자!", source = "PLAN"))   // 키 없이 이름만 — 같은 테마
        assertEquals(first, again, "source 도 안 바뀐다")
        assertEquals(PlayedSource.MANUAL, again.source)
        assertEquals(1, service.mine("나").size)

        val other = service.add("나", PlayedInput(themeName = "목격자", themeKey = "play33-hongdae:3"))
        assertEquals(2, service.mine("나").size, "다른 지점의 같은 이름 — 키가 둘 다 있고 다르면 다른 테마. ${other.id}")
    }

    @Test
    fun `PLAN 으로 기록할 수 있고, EVENT 는 안 된다`() {
        assertEquals(PlayedSource.PLAN, service.add("나", PlayedInput(themeName = "A", source = "PLAN")).source)
        assertEquals("BAD_PLAYED", code { service.add("나", PlayedInput(themeName = "B", source = "EVENT")) })
        assertEquals("BAD_PLAYED", code { service.add("나", PlayedInput(themeName = "B", source = "아무거나")) })
    }

    @Test
    fun `이름이 틀리면 BAD_PLAYED`() {
        val bad = listOf(
            PlayedInput(),
            PlayedInput(themeName = "   "),
            PlayedInput(themeName = "!!! 🙂"),                      // 정리하면 빈다 — 받으면 이런 이름끼리 전부 같다
            PlayedInput(themeName = "가".repeat(41)),
            PlayedInput(themeName = "A", branchName = "가".repeat(41)),
            PlayedInput(themeName = "A", themeKey = "k".repeat(81)),
            PlayedInput(themeName = "A", playedOn = "어제"),
        )
        bad.forEachIndexed { i, b -> assertEquals("BAD_PLAYED", code { service.add("나", b) }, "경우 $i") }
        // 이모지 40자가 들어가는지는 MigrationValidationTest(진짜 Postgres)가 본다 — H2 는 글자를 UTF-16 으로 세서 여기선 못 믿는다
    }

    @Test
    fun `내 기록만 지우고, 남의 것과 없는 것은 같은 404`() {
        val mine = service.add("나", PlayedInput(themeName = "A")).id
        val yours = service.add("너", PlayedInput(themeName = "A")).id
        assertEquals("NO_SUCH_PLAYED", code { service.delete("나", yours) })
        assertEquals("NO_SUCH_PLAYED", code { service.delete("나", 999_999) })
        assertEquals(DeletedDto(mine, true), service.delete("나", mine))
        assertTrue(service.mine("나").isEmpty())
    }

    @Test
    fun `멤버끼리 서로의 기록을 보고, 승인 안 된 사람은 못 본다`() {
        service.add("너", PlayedInput(themeName = "A"))
        assertEquals(listOf("A"), service.ofMember("나", "너").map { it.themeName })
        assertEquals("NO_SUCH_MEMBER", code { service.ofMember("나", "대기") })
        assertEquals("NO_SUCH_MEMBER", code { service.ofMember("나", "없는사람") })
        assertEquals("NOT_CLUB_MEMBER", code { service.ofMember("대기", "너") })
        assertEquals("NOT_CLUB_MEMBER", code { service.mine("대기") })
        assertEquals("NOT_CLUB_MEMBER", code { service.add("대기", PlayedInput(themeName = "A")) })
    }

    @Test
    fun `목록은 다녀온 날 최근 순, 날짜가 없으면 적은 날로`() {
        service.add("나", PlayedInput(themeName = "옛날", playedOn = "2020-01-01"))
        service.add("나", PlayedInput(themeName = "날짜 없음"))                       // 오늘 적었다
        service.add("나", PlayedInput(themeName = "미래", playedOn = "2999-01-01"))
        assertEquals(listOf("미래", "날짜 없음", "옛날"), service.mine("나").map { it.themeName })
    }

    // --- 자동 기록 ---

    @Test
    fun `지난 예약 완료 일정의 참여자 × 구간을 넣는다 — 이미 해 본 테마는 건너뛴다`() {
        val e = event()
        events.join("너", e.id)
        service.add("너", PlayedInput(themeName = "목격자"))   // 키 없이 — 키 있는 구간과 같은 테마

        assertEquals(1 to 3, recorder.record(today))   // 나 2개 + 너 1개(비밀의 화원)

        val mine = played.findByUserId("나")
        assertEquals(setOf("목격자", "비밀의 화원"), mine.map { it.themeName }.toSet())
        assertTrue(mine.all { it.source == PlayedSource.EVENT && it.eventId == e.id && it.playedOn == LocalDate.of(2026, 11, 7) })
        assertEquals(setOf(PlayedSource.MANUAL, PlayedSource.EVENT), played.findByUserId("너").map { it.source }.toSet())
    }

    @Test
    fun `일정마다 한 번만 — 사람이 지운 기록은 다시 안 넣는다`() {
        event()
        recorder.record(today)
        val removed = played.findByUserId("나").first()
        service.delete("나", requireNotNull(removed.id))

        assertEquals(0 to 0, recorder.record(today), "표시한 일정은 다시 안 본다")
        assertEquals(1, played.findByUserId("나").size)
    }

    @Test
    fun `자동 기록은 일정의 version 과 updatedAt 을 안 건드린다`() {
        // 엔티티로 표시를 달면 version 이 올라 그 일정을 열어 둔 사람이 04:30 작업 때문에 "다른 사람이 먼저 고쳤어요" 를 받는다
        val e = event()
        recorder.record(today)
        em.clear()
        val row = jdbc.queryForMap("SELECT version, updated_at, played_recorded_at FROM club_event WHERE id = ?", e.id)
        assertEquals(e.version, (row["version"] as Number).toLong())
        assertTrue(row["played_recorded_at"] != null)
        assertEquals(e.version, events.get("나", e.id).version)
        assertEquals(e.updatedAt, events.get("나", e.id).updatedAt)
        // 그리고 일정을 고쳐도 표시가 지워지지 않는다 (엔티티에서 읽기 전용)
        val edited = events.update("나", e.id, EventInput("고침", "2026-11-07", null, null, null, null, true, null, emptyList(), e.version))
        em.flush(); em.clear()
        assertTrue(jdbc.queryForMap("SELECT played_recorded_at FROM club_event WHERE id = ?", edited.id)["played_recorded_at"] != null)
    }

    @Test
    fun `예약 전 · 오늘 이후 · 날짜 미정 · 지운 일정은 건너뛴다`() {
        event(booked = false)
        event(date = today.toString())
        event(date = null)
        events.delete("나", event().id)
        assertEquals(0 to 0, recorder.record(today))
        assertTrue(played.findByUserId("나").isEmpty())
    }

    // --- 일정의 playedBy · 지난 일정 ---

    @Test
    fun `일정 구간마다 참여자 중 해 본 사람이 나오고, 참여자가 바뀌면 바로 바뀐다`() {
        val e = event(booked = false)
        service.add("너", PlayedInput(themeName = "목격자", themeKey = "play33-konkuk:18"))
        service.add("쟤", PlayedInput(themeName = "비밀의화원"))
        assertTrue(events.get("나", e.id).segments.all { it.playedBy.isEmpty() }, "참여자가 아니면 안 센다")

        events.join("너", e.id)
        events.join("쟤", e.id)
        assertEquals(listOf(listOf("너"), listOf("쟤")), events.get("나", e.id).segments.map { it.playedBy })

        events.leave("너", e.id)
        assertEquals(listOf(emptyList(), listOf("쟤")), events.get("나", e.id).segments.map { it.playedBy })
    }

    @Test
    fun `지난 일정은 내가 참여한 것만, 날짜 최근 순, 미정은 빼고`() {
        val old = event(date = "2026-10-01").id
        val newer = event(date = "2026-11-01").id
        event(date = today.toString())          // 오늘 — before 는 미포함
        event(date = null)
        events.create("너", EventInput("남의 것", "2026-10-15", null, null, null, null, false, null, listOf(seg("X")), null))

        assertEquals(listOf(newer, old), events.myPast("나", null, null, today = today).map { it.id })
        assertEquals(listOf(newer), events.myPast("나", null, "1", today = today).map { it.id })
        assertEquals(listOf(old), events.myPast("나", "2026-11-01", null, today = today).map { it.id })
        listOf<Pair<String?, String?>>("어제" to null, null to "0", null to "51", null to "열").forEach { (b, l) ->
            assertEquals("BAD_RANGE", code { events.myPast("나", b, l, today = today) }, "$b $l")
        }
    }
}
