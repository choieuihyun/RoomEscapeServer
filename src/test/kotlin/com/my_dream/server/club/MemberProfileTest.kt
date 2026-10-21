package com.my_dream.server.club

import com.my_dream.server.domain.ClubMember
import com.my_dream.server.domain.ClubMemberRepository
import com.my_dream.server.domain.MemberProfileRepository
import com.my_dream.server.domain.MemberRole
import com.my_dream.server.domain.MemberStatus
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 가입·프로필 고치기·멤버 목록에 붙는 프로필, 승인일 (§4.11 · D35).
 * 칸마다의 범위는 [ProfileRulesTest] 가 본다. 여기서는 **누가 언제 쓸 수 있나**와 저장 모양을 본다.
 */
@DataJpaTest
@Import(MembershipService::class, ClubEventService::class)
class MemberProfileTest @Autowired constructor(
    private val membership: MembershipService,
    private val events: ClubEventService,
    private val members: ClubMemberRepository,
    private val profiles: MemberProfileRepository,
) {

    private fun member(uid: String, status: MemberStatus = MemberStatus.APPROVED, role: MemberRole = MemberRole.MEMBER) =
        members.save(ClubMember(uid, "닉$uid", role, status, Instant.now()))

    private fun admin() = member("운영", role = MemberRole.ADMIN)

    private fun profile(name: String = "김철수", genres: List<Any?> = listOf("공포", "추리"), rooms: Any? = 120, residence: String? = null) = ProfileInput(
        realName = name,
        birthDate = "1997-06-28",
        mbti = "INTJ",
        roomCount = rooms,
        genres = genres,
        lifeTheme = LifeThemeInput("비밀의 화원", "강남점", null),
        residence = residence,
    )

    private fun assertClubError(code: String, status: HttpStatus, block: () -> Unit) {
        val e = assertFailsWith<ClubError> { block() }
        assertEquals(code, e.code)
        assertEquals(status, e.status)
    }

    // --- 가입 ---

    @Test
    fun `옛 앱처럼 닉네임만 보내도 가입 신청이 된다 — 프로필은 null`() {
        // 서버가 앱보다 먼저 올라간다. 이 경로가 막히면 그 사이 가입이 전부 400 이다
        val me = membership.join("u1", "철수")
        assertEquals("PENDING", me.status)
        assertNull(me.profile)
    }

    @Test
    fun `가입 신청에 프로필을 같이 보내면 저장되고 me 에 실린다`() {
        val me = membership.join("u1", "철수", profile())
        val p = assertNotNull(me.profile)
        assertEquals("김철수", p.realName)
        assertEquals(LocalDate.of(1997, 6, 28), p.birthDate)
        assertEquals(listOf("공포", "추리"), p.genres)
        assertEquals(LifeThemeDto("비밀의 화원", "강남점", null), p.lifeTheme)
        assertEquals(p, membership.me("u1").profile)
    }

    @Test
    fun `틀린 프로필이면 가입 신청 자체가 안 된다`() {
        assertClubError("BAD_PROFILE", HttpStatus.BAD_REQUEST) { membership.join("u1", "철수", profile(genres = emptyList())) }
        assertEquals("NONE", membership.me("u1").status, "멤버 행도 안 생긴다")
    }

    @Test
    fun `거절된 사람이 다시 신청하며 보낸 프로필은 새 값으로 바뀐다`() {
        membership.join("u1", "철수", profile(name = "옛이름"))
        members.findByUserId("u1")!!.status = MemberStatus.REJECTED
        val me = membership.join("u1", "철수", profile(name = "새이름"))
        assertEquals("PENDING", me.status)
        assertEquals("새이름", me.profile?.realName)
        assertEquals(1, profiles.count(), "한 사람 한 행")
    }

    @Test
    fun `내보낸 사람이 다시 신청할 때 — 프로필을 보내면 새 값, 안 보내면 있던 것`() {
        membership.join("u1", "철수", profile(name = "옛이름"))
        members.findByUserId("u1")!!.status = MemberStatus.REMOVED
        assertEquals("옛이름", membership.join("u1", "철수").profile?.realName)

        members.findByUserId("u1")!!.status = MemberStatus.REMOVED
        val me = membership.join("u1", "철수", profile(name = "새이름"))
        assertEquals("PENDING", me.status)
        assertEquals("새이름", me.profile?.realName)
        assertEquals(1, profiles.count())
    }

    @Test
    fun `승인된 사람의 가입 신청은 아무것도 안 바꾼다 — 고치는 길은 PUT 이다`() {
        member("u1")
        val me = membership.join("u1", "딴이름", profile())
        assertEquals("닉u1", me.nickname)
        assertNull(me.profile)
    }

    // --- 프로필 고치기 ---

    @Test
    fun `신청 전 · 거절된 사람 · 내보낸 사람은 NOT_JOINED`() {
        assertClubError("NOT_JOINED", HttpStatus.FORBIDDEN) { membership.updateProfile("없음", "철수", profile()) }
        member("거절", status = MemberStatus.REJECTED)
        assertClubError("NOT_JOINED", HttpStatus.FORBIDDEN) { membership.updateProfile("거절", "철수", profile()) }
        // 전에는 JOIN_BLOCKED 였다. 이제 다시 가입 신청하면 대기가 되어 고칠 수 있다 (D28 정정)
        member("내보냄", status = MemberStatus.REMOVED)
        assertClubError("NOT_JOINED", HttpStatus.FORBIDDEN) { membership.updateProfile("내보냄", "철수", profile()) }
        assertEquals(0, profiles.count())
    }

    @Test
    fun `거주지역 — 가입에 같이 저장되고, 통째로 바꾸는 PUT 에서 빠지면 지워진다`() {
        member("u1", status = MemberStatus.PENDING)
        assertEquals("서울 마포구", membership.join("u1", "철수", profile(residence = "서울 마포구")).profile?.residence)
        assertEquals("서울 마포구", membership.me("u1").profile?.residence)
        assertEquals("부산", membership.updateProfile("u1", "철수", profile(residence = "부산")).profile?.residence)
        // §4.11 "부분 수정 없음" — 칸을 모르는 옛 앱이 고치면 지워진다. 화면이 "채워 주세요" 줄로 다시 받는다
        assertNull(membership.updateProfile("u1", "철수", profile()).profile?.residence)
        assertEquals(1, profiles.count())
    }

    @Test
    fun `대기 중에도 고칠 수 있다`() {
        member("u1", status = MemberStatus.PENDING)
        assertEquals("김철수", membership.updateProfile("u1", "철수", profile()).profile?.realName)
    }

    @Test
    fun `승인된 멤버는 닉네임까지 통째로 바꾼다 — 두 번째 저장은 같은 행을 덮는다`() {
        member("u1")
        membership.updateProfile("u1", "철수", profile(name = "처음"))
        val me = membership.updateProfile("u1", " 영희 ", profile(name = "나중", genres = listOf("SF")))
        assertEquals("영희", me.nickname, "1단계의 '승인 뒤엔 닉네임 고정' 을 뒤집었다 (기획 6단계)")
        assertEquals("나중", me.profile?.realName)
        assertEquals(listOf("SF"), me.profile?.genres)
        assertEquals(1, profiles.count())
    }

    @Test
    fun `프로필을 빼고 보내거나 닉네임이 틀리면 거절`() {
        member("u1")
        assertClubError("BAD_PROFILE", HttpStatus.BAD_REQUEST) { membership.updateProfile("u1", "철수", null) }
        assertClubError("BAD_NICKNAME", HttpStatus.BAD_REQUEST) { membership.updateProfile("u1", "  ", profile()) }
        assertEquals("닉u1", members.findByUserId("u1")!!.nickname)
    }

    // --- 승인일 ---

    @Test
    fun `승인일은 승인할 때만 찍힌다 — 역할 바꾸기·내보내기로는 안 바뀐다`() {
        admin()
        member("u1", status = MemberStatus.PENDING)
        membership.approve("운영", "u1")
        val approvedAt = assertNotNull(members.findByUserId("u1")!!.approvedAt)

        membership.setRole("운영", "u1", MemberRole.ADMIN)
        assertEquals(approvedAt, members.findByUserId("u1")!!.approvedAt)
        membership.remove("운영", "u1")
        assertEquals(approvedAt, members.findByUserId("u1")!!.approvedAt)
    }

    // --- 목록에 붙는 프로필 ---

    @Test
    fun `운영진 멤버 관리 목록에도 프로필이 보인다`() {
        admin()
        member("u1", status = MemberStatus.PENDING)
        membership.updateProfile("u1", "철수", profile())
        val rows = membership.list("운영", "ALL").associateBy { it.uid }
        assertEquals("김철수", rows["u1"]?.profile?.realName)
        assertNull(rows["운영"]?.profile)
    }

    @Test
    fun `멤버 목록 — 승인 멤버만, 역할 · 승인일 · 프로필, 안 채운 사람은 null`() {
        admin()
        member("u1", status = MemberStatus.PENDING)
        membership.approve("운영", "u1")
        membership.updateProfile("u1", "가람", profile())
        member("대기", status = MemberStatus.PENDING)

        val list = events.members("운영")
        assertEquals(listOf("가람", "닉운영"), list.map { it.nickname }, "가나다순 · 대기 중은 빠진다")
        val u1 = list.first { it.uid == "u1" }
        assertEquals("MEMBER", u1.role)
        assertNotNull(u1.approvedAt)
        assertEquals("김철수", u1.profile?.realName)
        val boss = list.first { it.uid == "운영" }
        assertEquals("ADMIN", boss.role)
        assertNull(boss.approvedAt, "승인일을 모르는 기존 멤버는 null")
        assertNull(boss.profile)
    }
}
