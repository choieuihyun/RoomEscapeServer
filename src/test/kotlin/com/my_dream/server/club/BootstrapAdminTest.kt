package com.my_dream.server.club

import com.my_dream.server.domain.ClubMember
import com.my_dream.server.domain.ClubMemberRepository
import com.my_dream.server.domain.MemberRole
import com.my_dream.server.domain.MemberStatus
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import java.time.Instant
import kotlin.test.assertEquals

/**
 * 첫 관리자. **관리자가 0명이면 아무도 승인을 못 한다** — 그래서 환경변수로 한 명을 심는다.
 *
 * "관리자가 한 명도 없을 때만" 움직인다. 이미 관리자가 있는데 기동할 때마다 이 uid 를 되살리면,
 * 사용자가 화면에서 스스로 내려온 것을 재시작이 조용히 뒤집는다.
 */
@DataJpaTest
class BootstrapAdminTest @Autowired constructor(
    private val members: ClubMemberRepository,
) {

    private fun run(uid: String) = BootstrapAdmin(members, uid).run(DefaultApplicationArguments())

    @Test
    fun `프로퍼티 uid 를 승인된 관리자로 만든다`() {
        run("내-uid")
        val me = members.findByUserId("내-uid")!!
        assertEquals(MemberRole.ADMIN, me.role)
        assertEquals(MemberStatus.APPROVED, me.status)
        assertEquals("관리자", me.nickname)
    }

    @Test
    fun `두 번 돌려도 행은 하나`() {
        run("내-uid")
        run("내-uid")
        assertEquals(1, members.count())
    }

    @Test
    fun `먼저 가입 신청해 둔 계정이면 그 행을 올린다`() {
        // 배포 순서상 흔하다 — 화면이 먼저 열려서 본인이 가입 신청부터 눌렀다
        members.save(ClubMember("내-uid", "방장", MemberRole.MEMBER, MemberStatus.PENDING, Instant.now()))
        run("내-uid")
        val me = members.findByUserId("내-uid")!!
        assertEquals(MemberRole.ADMIN, me.role)
        assertEquals(MemberStatus.APPROVED, me.status)
        assertEquals("방장", me.nickname, "적어 둔 닉네임은 그대로")
    }

    @Test
    fun `관리자가 이미 있으면 손대지 않는다`() {
        members.save(ClubMember("다른-관리자", "방장2", MemberRole.ADMIN, MemberStatus.APPROVED, Instant.now()))
        members.save(ClubMember("내-uid", "방장", MemberRole.MEMBER, MemberStatus.APPROVED, Instant.now()))
        run("내-uid")
        assertEquals(MemberRole.MEMBER, members.findByUserId("내-uid")!!.role)
    }

    @Test
    fun `비어 있으면 아무것도 안 한다`() {
        run("")
        assertEquals(0, members.count())
    }
}
