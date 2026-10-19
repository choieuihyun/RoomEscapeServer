package com.my_dream.server.club

import com.my_dream.server.domain.ClubEventRepository
import com.my_dream.server.domain.ClubMember
import com.my_dream.server.domain.ClubMemberRepository
import com.my_dream.server.domain.MemberRole
import com.my_dream.server.domain.MemberStatus
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 동호회 일정 — 만들기·수정·삭제·참여·예약 상태. 계약은 Floduler `작업명세서.md` §4.7.
 * **권한은 서버가 판단한다** — 화면이 단추를 숨겨도 여기서 다시 막혀야 한다.
 *
 * 동시성(마지막 자리 · 같은 version 두 수정)은 진짜 Postgres 로 `ClubEventConcurrencyTest` 가 본다.
 */
@DataJpaTest
@Import(ClubEventService::class)
class ClubEventServiceTest @Autowired constructor(
    private val service: ClubEventService,
    private val members: ClubMemberRepository,
    private val events: ClubEventRepository,
) {

    private val day = LocalDate.of(2026, 11, 7)

    @BeforeEach
    fun setUp() {
        member("나")
        member("너")
        member("쟤")
        member("운영", role = MemberRole.ADMIN)
        member("대기", status = MemberStatus.PENDING)
        member("내보냄", status = MemberStatus.REMOVED)
    }

    private fun member(uid: String, role: MemberRole = MemberRole.MEMBER, status: MemberStatus = MemberStatus.APPROVED) =
        members.save(ClubMember(uid, "닉$uid", role, status, Instant.now()))

    private fun input(
        title: String? = "보드게임처럼",
        date: String? = day.toString(),
        startMin: Int? = null,
        endMin: Int? = null,
        capacity: Int? = null,
        booked: Boolean? = false,
        memo: String? = null,
        location: String? = null,
        segments: List<SegmentInput>? = emptyList(),
        version: Long? = null,
        candidates: List<Any?>? = null,
    ) = EventInput(title, date, startMin, endMin, location, capacity, booked, memo, segments, version, candidates)

    /** 날짜 미정 일정 (§4.8) */
    private fun undecided(vararg dates: String, version: Long? = null) = input(date = null, candidates = dates.toList(), version = version)

    private fun yes(date: String, start: Int? = null, end: Int? = null) = AvailabilityInput(date, true, start, end)

    private fun seg(name: String, start: Int, end: Int? = null, key: String? = null) =
        SegmentInput(themeName = name, branchName = "강남점", themeKey = key, startMin = start, endMin = end)

    private fun assertClubError(code: String, status: HttpStatus, block: () -> Unit): ClubError {
        val e = assertFailsWith<ClubError> { block() }
        assertEquals(code, e.code)
        assertEquals(status, e.status)
        return e
    }

    // --- 들어올 수 있는 사람 ---

    @Test
    fun `승인 안 된 사람은 일정 API 를 하나도 못 쓴다`() {
        val id = service.create("나", input()).id
        for (uid in listOf("대기", "내보냄", "가입안함")) {
            assertClubError("NOT_CLUB_MEMBER", HttpStatus.FORBIDDEN) { service.list(uid, "2026-11-01", "2026-11-30") }
            assertClubError("NOT_CLUB_MEMBER", HttpStatus.FORBIDDEN) { service.get(uid, id) }
            assertClubError("NOT_CLUB_MEMBER", HttpStatus.FORBIDDEN) { service.create(uid, input()) }
            assertClubError("NOT_CLUB_MEMBER", HttpStatus.FORBIDDEN) { service.join(uid, id) }
            assertClubError("NOT_CLUB_MEMBER", HttpStatus.FORBIDDEN) { service.members(uid) }
        }
    }

    // --- 만들기 ---

    @Test
    fun `만들면 만든 사람이 자동으로 참여한다`() {
        val e = service.create("나", input(capacity = 4))
        assertEquals(listOf("나"), e.participants.map { it.uid })
        assertNull(e.participants.single().addedBy, "스스로 참여한 것이다")
        assertEquals(1, e.count)
        assertTrue(e.joined)
        assertTrue(e.mine)
        assertTrue(e.canManage)
        assertEquals("닉나", e.createdBy.nickname)
    }

    @Test
    fun `제목과 구간이 둘 다 비면 BAD_EVENT`() {
        assertClubError("BAD_EVENT", HttpStatus.BAD_REQUEST) { service.create("나", input(title = "  ")) }
        // 구간이 있으면 제목은 비어도 된다 — 화면이 구간으로 제목을 만든다(저장은 빈 채로)
        val e = service.create("나", input(title = "  ", segments = listOf(seg("ABC", 820, 890))))
        assertEquals("", e.title)
    }

    @Test
    fun `범위를 벗어난 값은 BAD_EVENT`() {
        val bad = listOf(
            input(date = "2026-13-01"),
            input(date = null),
            input(booked = null),
            input(endMin = 600),                           // 끝만 있음 (시작만 있는 것은 된다)
            input(startMin = 2880),                        // 시작이 다음날 23:59 넘음
            input(startMin = 700, endMin = 600),           // 끝이 먼저
            input(startMin = 0, endMin = 2880),            // 다음날 23:59 넘음
            input(capacity = 1),
            input(capacity = 9),
            input(title = "가".repeat(61)),
            input(location = "가".repeat(61)),
            input(memo = "가".repeat(501)),
            input(segments = List(7) { seg("T$it", 600 + it) }),
            input(segments = listOf(seg("", 600))),
            input(segments = listOf(seg("T", 600, 600))),
            input(segments = listOf(seg("T", 2880))),
            input(segments = listOf(seg("T", 600, key = "k".repeat(81)))),
        )
        bad.forEachIndexed { i, b ->
            val e = assertFailsWith<ClubError>("경우 $i") { service.create("나", b) }
            assertEquals("BAD_EVENT", e.code, "경우 $i")
        }
        assertEquals(0, events.count())
    }

    @Test
    fun `시작 시각만 있는 일정도 만들고 고칠 수 있다`() {
        val made = service.create("나", input(startMin = 1140))   // "19:00 모임"
        assertEquals(1140, made.startMin)
        assertNull(made.endMin)

        val moved = service.update("나", made.id, input(startMin = 1470, version = made.version))   // 다음날 00:30
        assertEquals(1470, moved.startMin)
        assertNull(moved.endMin)

        val ranged = service.update("나", made.id, input(startMin = 1140, endMin = 1260, version = moved.version))
        assertEquals(1260, ranged.endMin, "시작만 → 시작·끝으로도 바뀐다")
    }

    @Test
    fun `빈 위치·메모는 null 로, 메모의 줄바꿈은 그대로`() {
        val e = service.create("나", input(location = "  ", memo = "  첫 줄\n둘째 줄  "))
        assertNull(e.location)
        assertEquals("첫 줄\n둘째 줄", e.memo)
    }

    @Test
    fun `자정 넘는 회차와 서버에 없는 테마 키도 저장된다`() {
        val e = service.create("나", input(segments = listOf(seg("심야", 1470, 1550, key = "어디에도-없는:키"))))
        assertEquals(1470, e.segments.single().startMin)
        assertEquals("어디에도-없는:키", e.segments.single().themeKey)
    }

    // --- 수정 · version ---

    @Test
    fun `수정하면 version 이 오르고, 받은 version 으로 또 고칠 수 있다`() {
        val made = service.create("나", input(segments = listOf(seg("ABC", 820, 890), seg("DEF", 920, 990))))
        val once = service.update("나", made.id, input(title = "바꿈", segments = listOf(seg("GHI", 800), seg("JKL", 900)), version = made.version))
        assertEquals(made.version + 1, once.version, "응답에 새 번호가 실려야 한다 — 옛 번호면 다음 수정이 자기 자신과 충돌한다")
        assertEquals(listOf("GHI", "JKL"), once.segments.map { it.themeName }, "겹치는 position 으로 통째로 바꿔도 된다")

        val twice = service.update("나", made.id, input(title = "또", version = once.version))
        assertEquals(once.version + 1, twice.version)
        assertTrue(twice.segments.isEmpty(), "빈 목록은 구간을 다 뺀다는 뜻이다")
    }

    @Test
    fun `같은 내용으로 고쳐도 version 은 오른다`() {
        val made = service.create("나", input())
        assertEquals(made.version + 1, service.update("나", made.id, input(version = made.version)).version)
    }

    @Test
    fun `받아 둔 version 이 낡았으면 덮어쓰지 않고 최신을 준다`() {
        val made = service.create("나", input(title = "처음"))
        service.update("나", made.id, input(title = "앞 사람", version = made.version))

        val e = assertClubError("EVENT_CONFLICT", HttpStatus.CONFLICT) {
            service.update("나", made.id, input(title = "뒷 사람", version = made.version))
        }
        val latest = e.extra["event"] as EventDto
        assertEquals("앞 사람", latest.title)
        assertEquals("앞 사람", service.get("나", made.id).title, "뒷 사람 것은 저장되지 않았다")
    }

    @Test
    fun `수정에 version 이 없으면 BAD_EVENT`() {
        val made = service.create("나", input())
        assertClubError("BAD_EVENT", HttpStatus.BAD_REQUEST) { service.update("나", made.id, input(version = null)) }
    }

    @Test
    fun `만든 사람과 운영진만 고친다`() {
        val made = service.create("나", input())
        assertClubError("NOT_EVENT_OWNER", HttpStatus.FORBIDDEN) { service.update("너", made.id, input(version = made.version)) }
        assertEquals("운영진 수정", service.update("운영", made.id, input(title = "운영진 수정", version = made.version)).title)
    }

    @Test
    fun `정원을 지금 참여자보다 적게 못 줄인다`() {
        val made = service.create("나", input(capacity = 4))
        service.join("너", made.id)
        service.join("쟤", made.id)
        assertClubError("CAPACITY_TOO_SMALL", HttpStatus.CONFLICT) { service.update("나", made.id, input(capacity = 2, version = made.version)) }
        assertEquals(3, service.update("나", made.id, input(capacity = 3, version = made.version)).capacity)
    }

    // --- 참여 ---

    @Test
    fun `참여와 취소는 version 도 updatedAt 도 안 바꾼다`() {
        // 편집 중인 사람이 남의 참여 때문에 충돌을 받으면 안 된다 (D31)
        val made = service.create("나", input(capacity = 4))
        service.join("너", made.id)
        service.leave("너", made.id)
        service.addParticipant("나", made.id, "쟤")
        service.removeParticipant("나", made.id, "쟤")
        val now = service.get("나", made.id)
        assertEquals(made.version, now.version)
        assertEquals(made.updatedAt, now.updatedAt)
        // 그래서 처음 받은 version 으로 수정이 된다
        service.update("나", made.id, input(title = "그대로 됨", version = made.version))
    }

    @Test
    fun `두 번 참여해도 한 자리고, 꽉 찬 일정에 이미 있는 사람은 그대로 200`() {
        val made = service.create("나", input(capacity = 2))
        service.join("너", made.id)
        assertEquals(2, service.join("너", made.id).count)
        assertClubError("EVENT_FULL", HttpStatus.CONFLICT) { service.join("쟤", made.id) }
        // 정원을 세기 전에 "이미 있나" 를 본다 — 들어가 있는 사람이 "자리가 다 찼어요" 를 받으면 안 된다
        assertEquals(2, service.join("나", made.id).count)
    }

    @Test
    fun `만든 사람도 참여를 취소할 수 있고 관리 권한은 남는다`() {
        val made = service.create("나", input())
        val left = service.leave("나", made.id)
        assertFalse(left.joined)
        assertTrue(left.mine)
        assertTrue(left.canManage)
        assertEquals(0, left.count)
        // 참여 안 한 사람의 취소는 그대로 200
        assertEquals(0, service.leave("나", made.id).count)
    }

    @Test
    fun `대신 넣기와 빼기는 만든 사람과 운영진만`() {
        val made = service.create("나", input(capacity = 2))
        assertClubError("NOT_EVENT_OWNER", HttpStatus.FORBIDDEN) { service.addParticipant("너", made.id, "쟤") }

        val added = service.addParticipant("운영", made.id, "쟤")
        assertEquals("운영", added.participants.first { it.uid == "쟤" }.addedBy)
        assertEquals(2, service.addParticipant("나", made.id, "쟤").count, "이미 있으면 그대로")
        assertClubError("EVENT_FULL", HttpStatus.CONFLICT) { service.addParticipant("나", made.id, "너") }

        assertClubError("NOT_EVENT_OWNER", HttpStatus.FORBIDDEN) { service.removeParticipant("너", made.id, "쟤") }
        assertEquals(1, service.removeParticipant("나", made.id, "쟤").count)
        assertEquals(1, service.removeParticipant("나", made.id, "쟤").count, "없으면 그대로")
    }

    @Test
    fun `승인 멤버가 아니면 대신 넣을 수 없다`() {
        val made = service.create("나", input())
        for (uid in listOf("대기", "내보냄", "가입안함")) {
            assertClubError("NO_SUCH_MEMBER", HttpStatus.NOT_FOUND) { service.addParticipant("나", made.id, uid) }
        }
    }

    // --- 예약 상태 ---

    @Test
    fun `예약 상태는 참여자·만든 사람·운영진이 바꾸고, 바뀔 때만 version 이 오른다`() {
        val made = service.create("나", input())
        service.join("너", made.id)
        assertClubError("NOT_PARTICIPANT", HttpStatus.FORBIDDEN) { service.setBooking("쟤", made.id, true) }

        val booked = service.setBooking("너", made.id, true)
        assertTrue(booked.booked)
        assertEquals(made.version + 1, booked.version)
        assertEquals(booked.version, service.setBooking("운영", made.id, true).version, "같은 값이면 그대로")

        service.leave("나", made.id)
        assertFalse(service.setBooking("나", made.id, false).booked, "만든 사람은 참여를 취소했어도 바꾼다")
    }

    // --- 삭제 ---

    @Test
    fun `삭제는 표시만 하고, 그 뒤로는 전부 NO_SUCH_EVENT`() {
        val made = service.create("나", input())
        assertClubError("NOT_EVENT_OWNER", HttpStatus.FORBIDDEN) { service.delete("너", made.id) }
        assertEquals(DeletedDto(made.id, true), service.delete("나", made.id))

        assertTrue(events.findById(made.id).get().deletedAt != null, "행은 남는다")
        assertTrue(service.list("나", "2026-11-01", "2026-11-30").isEmpty())
        assertClubError("NO_SUCH_EVENT", HttpStatus.NOT_FOUND) { service.get("나", made.id) }
        assertClubError("NO_SUCH_EVENT", HttpStatus.NOT_FOUND) { service.join("너", made.id) }
        assertClubError("NO_SUCH_EVENT", HttpStatus.NOT_FOUND) { service.update("나", made.id, input(version = made.version)) }
        assertClubError("NO_SUCH_EVENT", HttpStatus.NOT_FOUND) { service.delete("나", made.id) }
    }

    @Test
    fun `운영진은 남의 일정도 지운다`() {
        val made = service.create("나", input())
        assertEquals(DeletedDto(made.id, true), service.delete("운영", made.id))
    }

    @Test
    fun `만든 사람이 내보내지면 운영진이 이어서 관리한다`() {
        val made = service.create("나", input())
        members.findByUserId("나")!!.status = MemberStatus.REMOVED

        assertClubError("NOT_CLUB_MEMBER", HttpStatus.FORBIDDEN) { service.update("나", made.id, input(version = made.version)) }
        val byAdmin = service.update("운영", made.id, input(title = "운영진이 고침", version = made.version))
        assertEquals("닉나", byAdmin.createdBy.nickname, "만든 사람 닉네임은 그대로 보인다")
        assertFalse(byAdmin.mine)
        assertTrue(byAdmin.canManage)
    }

    // --- 시간 협의 (§4.8 · D32) ---

    @Test
    fun `날짜 미정 일정은 날짜 없이 후보로 만들고, 후보는 날짜 순으로 나간다`() {
        val e = service.create("나", undecided("2026-11-09", "2026-11-07"))
        assertNull(e.date)
        assertEquals(listOf(LocalDate.of(2026, 11, 7), LocalDate.of(2026, 11, 9)), e.candidates.map { it.date })
        assertTrue(e.candidates.all { it.responses.isEmpty() })
    }

    @Test
    fun `후보가 틀리면 BAD_EVENT`() {
        val bad = listOf(
            input(date = null),                                          // 미정인데 후보 안 보냄
            undecided(),                                                 // 미정인데 후보 0개
            undecided(*Array(15) { "2026-11-%02d".format(it + 1) }),     // 15개
            undecided("2026-11-07", "2026-11-07"),                       // 중복
            undecided("11월 7일"),
            input(candidates = listOf(null)),
            input(candidates = listOf(mapOf("responses" to emptyList<Any>()))),   // 객체인데 date 없음
            input(candidates = listOf(20261107)),
        )
        bad.forEachIndexed { i, b -> assertEquals("BAD_EVENT", assertFailsWith<ClubError>("경우 $i") { service.create("나", b) }.code, "경우 $i") }
        assertEquals(0, events.count())
        // 날짜가 있으면 후보는 0~14개 아무거나 — 정한 뒤 남겨 두는 기록이다
        service.create("나", input(candidates = List(14) { "2026-11-%02d".format(it + 1) }))
    }

    @Test
    fun `참여자는 후보마다 응답하고, 같은 날짜는 덮어쓰며, version 은 그대로다`() {
        val made = service.create("나", undecided("2026-11-07", "2026-11-08"))
        service.join("너", made.id)
        service.answer("나", made.id, yes("2026-11-07", 1140, 1320))
        service.answer("너", made.id, AvailabilityInput("2026-11-07", false))
        val after = service.answer("너", made.id, yes("2026-11-07"))   // 마음이 바뀜 — 종일 됨

        val day7 = after.candidates.first { it.date == LocalDate.of(2026, 11, 7) }
        assertEquals(
            listOf(ResponseDto("나", true, 1140, 1320), ResponseDto("너", true, null, null)),
            day7.responses.sortedBy { it.uid },
        )
        assertEquals(made.version, after.version, "응답은 편집 충돌을 일으키지 않는다 (D31 의 참여와 같다)")
        assertEquals(made.updatedAt, after.updatedAt)
    }

    @Test
    fun `응답 모양이 틀리면 BAD_AVAILABILITY, 후보가 아니면 NOT_A_CANDIDATE, 참여자가 아니면 403`() {
        val id = service.create("나", undecided("2026-11-07")).id
        val bad = listOf(
            AvailabilityInput(null, true),
            AvailabilityInput("내일", true),
            AvailabilityInput("2026-11-07", null),
            AvailabilityInput("2026-11-07", false, 600, 700),   // 안 되는데 시간대
            AvailabilityInput("2026-11-07", true, 600, null),   // 하나만
            AvailabilityInput("2026-11-07", true, null, 700),
            AvailabilityInput("2026-11-07", true, 700, 700),
            AvailabilityInput("2026-11-07", true, 700, 2880),
            AvailabilityInput("2026-11-07", true, -1, 700),
        )
        bad.forEachIndexed { i, b ->
            assertEquals("BAD_AVAILABILITY", assertFailsWith<ClubError>("경우 $i") { service.answer("나", id, b) }.code, "경우 $i")
        }
        assertClubError("NOT_A_CANDIDATE", HttpStatus.BAD_REQUEST) { service.answer("나", id, yes("2026-11-08")) }
        assertClubError("NOT_PARTICIPANT_ANSWER", HttpStatus.FORBIDDEN) { service.answer("너", id, yes("2026-11-07")) }
        assertClubError("NOT_PARTICIPANT_ANSWER", HttpStatus.FORBIDDEN) { service.clearAnswer("너", id, "2026-11-07") }
        assertClubError("NOT_CLUB_MEMBER", HttpStatus.FORBIDDEN) { service.answer("대기", id, yes("2026-11-07")) }
        assertClubError("NO_SUCH_EVENT", HttpStatus.NOT_FOUND) { service.answer("나", 999_999, yes("2026-11-07")) }
        service.delete("나", id)
        assertClubError("NO_SUCH_EVENT", HttpStatus.NOT_FOUND) { service.answer("나", id, yes("2026-11-07")) }
    }

    @Test
    fun `응답 지우기 — 없거나 후보가 아니어도 200, 날짜 형식이 틀리면 BAD_AVAILABILITY`() {
        val id = service.create("나", undecided("2026-11-07")).id
        service.answer("나", id, yes("2026-11-07"))
        assertTrue(service.clearAnswer("나", id, "2026-11-07").candidates.single().responses.isEmpty())
        service.clearAnswer("나", id, "2026-11-07")
        service.clearAnswer("나", id, "2026-12-25")
        assertClubError("BAD_AVAILABILITY", HttpStatus.BAD_REQUEST) { service.clearAnswer("나", id, "크리스마스") }
    }

    @Test
    fun `참여를 취소하거나 빠지면 그 사람 응답도 지워진다`() {
        val id = service.create("나", undecided("2026-11-07", "2026-11-08")).id
        listOf("너", "쟤").forEach { service.join(it, id) }
        listOf("나", "너", "쟤").forEach { u -> listOf("2026-11-07", "2026-11-08").forEach { service.answer(u, id, yes(it)) } }

        service.leave("너", id)
        val after = service.removeParticipant("나", id, "쟤")
        assertTrue(after.candidates.all { c -> c.responses.map { it.uid } == listOf("나") }, "${after.candidates}")
    }

    @Test
    fun `수정에 후보를 안 보내면 그대로 — 이미 배포된 2단계 화면이 미정 일정을 고쳐도 안 날아간다`() {
        val made = service.create("나", undecided("2026-11-07"))
        service.answer("나", made.id, yes("2026-11-07"))
        // 2단계 화면은 date 를 늘 채워 보내지만, 받은 date(null) 를 그대로 돌려보내는 경우도 같이 본다
        val edited = service.update("나", made.id, input(title = "제목만", date = null, version = made.version))
        assertEquals("제목만", edited.title)
        assertEquals(1, edited.candidates.single().responses.size)
    }

    @Test
    fun `후보는 차이만 바뀐다 — 남은 날짜의 응답은 살고, 뺀 날짜의 응답은 지워진다`() {
        val made = service.create("나", undecided("2026-11-07", "2026-11-08"))
        service.answer("나", made.id, yes("2026-11-07", 600, 700))
        service.answer("나", made.id, yes("2026-11-08"))

        val edited = service.update("나", made.id, undecided("2026-11-07", "2026-11-09", version = made.version))
        assertEquals(listOf("2026-11-07", "2026-11-09"), edited.candidates.map { it.date.toString() })
        assertEquals(listOf(ResponseDto("나", true, 600, 700)), edited.candidates[0].responses)
        assertTrue(edited.candidates[1].responses.isEmpty())

        // 다시 8일을 넣어도 지운 응답은 돌아오지 않는다
        val again = service.update("나", made.id, undecided("2026-11-07", "2026-11-08", version = edited.version))
        assertTrue(again.candidates[1].responses.isEmpty())
    }

    @Test
    fun `정하기와 다시 미정은 수정으로 — 정한 뒤에도 후보와 응답이 남고 응답을 받는다`() {
        val made = service.create("나", undecided("2026-11-07", "2026-11-08"))
        service.answer("나", made.id, yes("2026-11-08"))

        val decided = service.update("나", made.id, input(date = "2026-11-08", version = made.version))
        assertEquals(LocalDate.of(2026, 11, 8), decided.date)
        assertEquals(2, decided.candidates.size)
        service.answer("나", made.id, yes("2026-11-07"))

        val reopened = service.update("나", made.id, input(date = null, version = decided.version))
        assertNull(reopened.date)
        assertTrue(reopened.candidates.all { it.responses.size == 1 })

        // 후보를 다 지우면서 미정으로 둘 수는 없다. 날짜를 정하면서 지우는 것은 된다
        assertClubError("BAD_EVENT", HttpStatus.BAD_REQUEST) { service.update("나", made.id, undecided(version = reopened.version)) }
        val cleared = service.update("나", made.id, input(date = "2026-11-08", candidates = emptyList(), version = reopened.version))
        assertTrue(cleared.candidates.isEmpty())
        assertClubError("BAD_EVENT", HttpStatus.BAD_REQUEST) { service.update("나", made.id, input(date = null, version = cleared.version)) }
    }

    @Test
    fun `후보는 만든 사람과 운영진만 바꾼다`() {
        val made = service.create("나", undecided("2026-11-07"))
        service.join("너", made.id)
        assertClubError("NOT_EVENT_OWNER", HttpStatus.FORBIDDEN) { service.update("너", made.id, undecided("2026-11-08", version = made.version)) }
        service.update("운영", made.id, undecided("2026-11-08", version = made.version))
    }

    @Test
    fun `목록에는 후보가 범위 안인 미정 일정도 나오고, 범위 안 가장 이른 후보 자리에 놓인다`() {
        val fixed10 = service.create("나", input(date = "2026-11-10", startMin = 600)).id
        val fixed10AllDay = service.create("나", input(date = "2026-11-10")).id
        val open = service.create("나", undecided("2026-10-30", "2026-11-10", "2026-11-20")).id   // 범위 안 첫 후보 = 11-10
        val early = service.create("나", undecided("2026-11-02")).id
        service.create("나", undecided("2026-12-01"))                                                // 범위 밖

        val ids = service.list("나", "2026-11-01", "2026-11-15").map { it.id }
        // 10일: 시각 없는 둘(정한 하루 종일 · 미정)이 먼저, 그 둘은 id 순 → 그다음 10:00
        assertEquals(listOf(early, fixed10AllDay, open, fixed10), ids)
    }

    // --- 목록 ---

    @Test
    fun `목록은 날짜 범위 양끝 포함, 62일까지`() {
        service.create("나", input(date = "2026-11-01"))
        service.create("나", input(date = "2027-01-01"))
        assertEquals(2, service.list("나", "2026-11-01", "2027-01-01").size, "61일 차이 = 62일치 — 된다")
        assertClubError("BAD_RANGE", HttpStatus.BAD_REQUEST) { service.list("나", "2026-11-01", "2027-01-02") }
        assertClubError("BAD_RANGE", HttpStatus.BAD_REQUEST) { service.list("나", "2026-11-02", "2026-11-01") }
        assertClubError("BAD_RANGE", HttpStatus.BAD_REQUEST) { service.list("나", "어제", "2026-11-01") }
        assertClubError("BAD_RANGE", HttpStatus.BAD_REQUEST) { service.list("나", null, "2026-11-01") }
    }

    @Test
    fun `목록의 joined·mine·canManage 는 보는 사람 기준이다`() {
        val made = service.create("나", input())
        service.join("너", made.id)
        val forYou = service.list("너", "2026-11-01", "2026-11-30").single()
        assertTrue(forYou.joined)
        assertFalse(forYou.mine)
        assertFalse(forYou.canManage)
        val forThem = service.list("쟤", "2026-11-01", "2026-11-30").single()
        assertFalse(forThem.joined)
        assertEquals(listOf("나", "너"), forThem.participants.map { it.uid }, "참여 순")
    }

    @Test
    fun `내보낸 사람의 참여와 닉네임은 남는다`() {
        val made = service.create("나", input())
        service.join("너", made.id)
        members.findByUserId("너")!!.status = MemberStatus.REMOVED
        assertEquals("닉너", service.get("나", made.id).participants.first { it.uid == "너" }.nickname)
    }

    // --- 멤버 목록 ---

    @Test
    fun `대신 넣을 사람 목록은 승인 멤버만, 가나다순`() {
        members.findByUserId("나")!!.nickname = "하늘"
        members.findByUserId("너")!!.nickname = "가람"
        members.findByUserId("쟤")!!.nickname = "나무"
        members.findByUserId("운영")!!.nickname = "다솜"
        assertEquals(listOf("가람", "나무", "다솜", "하늘"), service.members("나").map { it.nickname })
    }
}
