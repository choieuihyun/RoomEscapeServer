package com.my_dream.server.club

import com.my_dream.server.domain.ClubMember
import com.my_dream.server.domain.ClubMemberRepository
import com.my_dream.server.domain.DeviceToken
import com.my_dream.server.domain.DeviceTokenRepository
import com.my_dream.server.domain.KakaoLink
import com.my_dream.server.domain.KakaoLinkRepository
import com.my_dream.server.domain.MemberRole
import com.my_dream.server.domain.MemberStatus
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 가입 · 승인 · 역할. 상태 전이는 Floduler `작업명세서.md` §4.6 "멤버 상태 전이" 표 그대로다.
 * **권한은 서버가 판단한다** — 화면이 버튼을 숨겨도 여기서 다시 막혀야 한다.
 */
@DataJpaTest
@Import(MembershipService::class)
class MembershipServiceTest @Autowired constructor(
    private val service: MembershipService,
    private val members: ClubMemberRepository,
    private val kakaoLinks: KakaoLinkRepository,
    private val devices: DeviceTokenRepository,
) {

    private fun admin(uid: String = "관리자") =
        members.save(ClubMember(uid, "방장", MemberRole.ADMIN, MemberStatus.APPROVED, Instant.now()))

    private fun member(uid: String, status: MemberStatus = MemberStatus.APPROVED, role: MemberRole = MemberRole.MEMBER) =
        members.save(ClubMember(uid, "닉$uid", role, status, Instant.now()))

    private fun assertClubError(code: String, status: HttpStatus, block: () -> Unit) {
        val e = assertFailsWith<ClubError> { block() }
        assertEquals(code, e.code)
        assertEquals(status, e.status)
    }

    // --- 가입 ---

    @Test
    fun `가입 전에는 NONE 이고 닉네임과 역할이 없다`() {
        val me = service.me("u1")
        assertEquals("NONE", me.status)
        assertNull(me.nickname)
        assertNull(me.role)
        assertFalse(me.kakaoLinked)
    }

    @Test
    fun `처음 가입하면 승인 대기다`() {
        val me = service.join("u1", " 철수 ")
        assertEquals("PENDING", me.status)
        assertEquals("철수", me.nickname, "앞뒤 공백은 지운다")
        assertEquals("MEMBER", me.role)
    }

    @Test
    fun `닉네임은 1~20자`() {
        assertClubError("BAD_NICKNAME", HttpStatus.BAD_REQUEST) { service.join("u1", "   ") }
        assertClubError("BAD_NICKNAME", HttpStatus.BAD_REQUEST) { service.join("u1", "가".repeat(21)) }
        assertEquals("가".repeat(20), service.join("u1", "가".repeat(20)).nickname)
    }

    @Test
    fun `이모지 하나는 한 글자로 센다`() {
        // Postgres 의 VARCHAR(20) 은 글자(코드 포인트)를 센다. String.length(UTF-16)로 세면
        // 이모지 닉네임이 서버 검사는 통과하는데 DB 에서 터지거나, 그 반대가 된다.
        // **저장까지는 여기서 안 본다** — H2 는 VARCHAR 를 UTF-16 으로 세서 운영과 다르다.
        // 20개가 실제로 들어가는지는 MigrationValidationTest(진짜 Postgres)가 본다
        assertEquals("🙂".repeat(20), MembershipService.cleanNickname("🙂".repeat(20)))
        assertClubError("BAD_NICKNAME", HttpStatus.BAD_REQUEST) { service.join("u2", "🙂".repeat(21)) }
    }

    @Test
    fun `가입은 여러 번 눌러도 하나`() {
        service.join("u1", "철수")
        service.join("u1", "철수")
        assertEquals(1, members.count())
    }

    @Test
    fun `대기 중에 다시 신청하면 닉네임만 고친다`() {
        service.join("u1", "철쑤")
        assertEquals("철수", service.join("u1", "철수").nickname)
    }

    @Test
    fun `거절된 사람은 다시 신청하면 대기로`() {
        member("u1", MemberStatus.REJECTED).apply { decidedAt = Instant.now(); decidedBy = "관리자" }
        val me = service.join("u1", "철수")
        assertEquals("PENDING", me.status)
        // 거절 시각이 남아 있으면 화면이 "거절됨" 을 대기 상태 위에 그릴 수 있다
        assertNull(members.findByUserId("u1")!!.decidedAt)
    }

    @Test
    fun `내보낸 사람은 다시 못 들어온다`() {
        member("u1", MemberStatus.REMOVED)
        assertClubError("JOIN_BLOCKED", HttpStatus.FORBIDDEN) { service.join("u1", "철수") }
    }

    @Test
    fun `카카오가 연결돼 있으면 me 가 알려 준다`() {
        kakaoLinks.save(KakaoLink(42, "u1", Instant.now()))
        assertTrue(service.me("u1").kakaoLinked)
    }

    // --- 관리자 확인 ---

    @Test
    fun `관리자가 아니면 승인 못 한다`() {
        member("u1", MemberStatus.PENDING)
        member("u2")
        assertClubError("NOT_ADMIN", HttpStatus.FORBIDDEN) { service.approve("u2", "u1") }
        assertClubError("NOT_ADMIN", HttpStatus.FORBIDDEN) { service.list("u2", "ALL") }
    }

    @Test
    fun `내보낸 관리자는 관리자가 아니다`() {
        admin()
        member("전관리자", MemberStatus.REMOVED, MemberRole.ADMIN)
        member("u1", MemberStatus.PENDING)
        assertClubError("NOT_ADMIN", HttpStatus.FORBIDDEN) { service.approve("전관리자", "u1") }
    }

    @Test
    fun `관리자 확인이 대상 확인보다 먼저다`() {
        // 반대면 관리자가 아닌 사람이 404/200 차이로 남의 가입 여부를 알아낼 수 있다
        member("u2")
        assertClubError("NOT_ADMIN", HttpStatus.FORBIDDEN) { service.approve("u2", "없는사람") }
    }

    @Test
    fun `없는 사람`() {
        admin()
        assertClubError("NO_SUCH_MEMBER", HttpStatus.NOT_FOUND) { service.approve("관리자", "없는사람") }
        assertClubError("NO_SUCH_MEMBER", HttpStatus.NOT_FOUND) { service.reject("관리자", "없는사람") }
        assertClubError("NO_SUCH_MEMBER", HttpStatus.NOT_FOUND) { service.remove("관리자", "없는사람") }
        assertClubError("NO_SUCH_MEMBER", HttpStatus.NOT_FOUND) { service.setRole("관리자", "없는사람", MemberRole.ADMIN) }
    }

    // --- 승인 · 거절 · 내보내기 ---

    @Test
    fun `승인하면 decidedAt·decidedBy 가 남는다`() {
        admin()
        member("u1", MemberStatus.PENDING)
        val dto = service.approve("관리자", "u1")
        assertEquals("APPROVED", dto.status)
        assertNotNull(dto.decidedAt)
        assertEquals("관리자", members.findByUserId("u1")!!.decidedBy)
    }

    @Test
    fun `거절했던 사람도 승인할 수 있다`() {
        admin()
        member("u1", MemberStatus.REJECTED)
        assertEquals("APPROVED", service.approve("관리자", "u1").status)
    }

    @Test
    fun `승인과 거절과 내보내기는 두 번 해도 같다`() {
        admin()
        member("u1", MemberStatus.APPROVED)
        assertEquals("APPROVED", service.approve("관리자", "u1").status)
        member("u2", MemberStatus.REJECTED)
        assertEquals("REJECTED", service.reject("관리자", "u2").status)
        member("u3", MemberStatus.REMOVED)
        assertEquals("REMOVED", service.remove("관리자", "u3").status)
    }

    @Test
    fun `상태가 안 맞으면 BAD_STATE`() {
        admin()
        member("내보냄", MemberStatus.REMOVED)
        member("멤버", MemberStatus.APPROVED)
        member("대기", MemberStatus.PENDING)
        assertClubError("BAD_STATE", HttpStatus.CONFLICT) { service.approve("관리자", "내보냄") }
        assertClubError("BAD_STATE", HttpStatus.CONFLICT) { service.reject("관리자", "내보냄") }
        assertClubError("BAD_STATE", HttpStatus.CONFLICT) { service.reject("관리자", "멤버") }
        assertClubError("BAD_STATE", HttpStatus.CONFLICT) { service.setRole("관리자", "대기", MemberRole.ADMIN) }
    }

    @Test
    fun `대기 중인 사람도 내보낼 수 있다`() {
        // 가입 신청자를 영구히 막는 용도 (§4.6)
        admin()
        member("u1", MemberStatus.PENDING)
        assertEquals("REMOVED", service.remove("관리자", "u1").status)
        assertClubError("JOIN_BLOCKED", HttpStatus.FORBIDDEN) { service.join("u1", "철수") }
    }

    // --- 역할 · 마지막 관리자 ---

    @Test
    fun `멤버를 관리자로 지정한다`() {
        admin()
        member("u1")
        val dto = service.setRole("관리자", "u1", MemberRole.ADMIN)
        assertEquals("ADMIN", dto.role)
        assertEquals("APPROVED", dto.status, "역할만 바뀐다")
    }

    @Test
    fun `마지막 관리자는 자기를 내보내거나 멤버로 못 내린다`() {
        admin()
        assertClubError("LAST_ADMIN", HttpStatus.CONFLICT) { service.remove("관리자", "관리자") }
        assertClubError("LAST_ADMIN", HttpStatus.CONFLICT) { service.setRole("관리자", "관리자", MemberRole.MEMBER) }
        val me = members.findByUserId("관리자")!!
        assertEquals(MemberRole.ADMIN, me.role)
        assertEquals(MemberStatus.APPROVED, me.status)
    }

    @Test
    fun `관리자가 둘이면 하나는 내릴 수 있다`() {
        admin("A")
        admin("B")
        assertEquals("MEMBER", service.setRole("A", "B", MemberRole.MEMBER).role)
        // 이제 A 가 마지막이다
        assertClubError("LAST_ADMIN", HttpStatus.CONFLICT) { service.setRole("A", "A", MemberRole.MEMBER) }
    }

    @Test
    fun `관리자를 내보내면 역할도 내려간다`() {
        // REMOVED 인데 role=ADMIN 으로 남으면, 나중에 되살리는 길이 생겼을 때 관리자로 돌아온다
        admin("A")
        admin("B")
        val dto = service.remove("A", "B")
        assertEquals("REMOVED", dto.status)
        assertEquals("MEMBER", dto.role)
    }

    // --- 목록 ---

    @Test
    fun `목록은 상태별로, 가입 순으로, 기기 유무를 붙여 준다`() {
        admin()
        member("늦게", MemberStatus.PENDING).apply { }
        devices.save(DeviceToken("늦게", "토큰", "web", Instant.now()))
        member("멤버", MemberStatus.APPROVED)

        val pending = service.list("관리자", "PENDING")
        assertEquals(listOf("늦게"), pending.map { it.uid })
        assertTrue(pending.single().hasDevice)

        val all = service.list("관리자", "ALL")
        assertEquals(listOf("관리자", "늦게", "멤버"), all.map { it.uid })
        assertFalse(all.first { it.uid == "멤버" }.hasDevice)
    }
}
