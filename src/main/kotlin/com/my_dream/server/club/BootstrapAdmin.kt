package com.my_dream.server.club

import com.my_dream.server.domain.ClubMember
import com.my_dream.server.domain.ClubMemberRepository
import com.my_dream.server.domain.MemberRole
import com.my_dream.server.domain.MemberStatus
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * 첫 관리자를 심는다 — `CLUB_BOOTSTRAP_ADMIN_UID` (아키텍처 D28).
 *
 * 관리자가 0명이면 아무도 승인을 못 하고, 승인된 사람이 없으면 아무도 빈자리 알림을 못 건다(S7).
 * 그래서 기동할 때 한 명을 만든다. 값은 사용자 본인의 기존 계정 uid 다 (Firebase 콘솔 → Authentication).
 *
 * **승인된 관리자가 한 명도 없을 때만** 움직인다. 이미 있는데 기동할 때마다 이 uid 를 되살리면,
 * 다른 사람을 관리자로 세우고 스스로 내려온 것을 다음 배포가 조용히 뒤집는다.
 * 같은 이유로 이 값은 "관리자를 잃었을 때 되찾는 손잡이" 이기도 하다.
 */
@Component
class BootstrapAdmin(
    private val members: ClubMemberRepository,
    @param:Value("\${club.bootstrap-admin-uid:}") private val uid: String,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    override fun run(args: ApplicationArguments) {
        val target = uid.trim()
        if (target.isEmpty()) return
        val admins = members.findForUpdateByRoleAndStatus(MemberRole.ADMIN, MemberStatus.APPROVED)
        if (admins.isNotEmpty()) return

        val existing = members.findForUpdateByUserId(target)
        if (existing == null) {
            val now = Instant.now()
            members.save(ClubMember(target, DEFAULT_NICKNAME, MemberRole.ADMIN, MemberStatus.APPROVED, now, now, BY, approvedAt = now))
        } else {
            // 먼저 가입 신청을 눌러 둔 경우가 흔하다. 적어 둔 닉네임은 살린다
            existing.role = MemberRole.ADMIN
            // 이미 승인된 멤버를 운영진으로 올리는 것이면 승인일은 그대로다 (D35)
            if (existing.status != MemberStatus.APPROVED) existing.approvedAt = Instant.now()
            existing.status = MemberStatus.APPROVED
            existing.decidedAt = Instant.now()
            existing.decidedBy = BY
        }
        log.info("관리자가 없어서 {} 를 첫 관리자로 세웠다 (CLUB_BOOTSTRAP_ADMIN_UID)", target)
    }

    companion object {
        const val DEFAULT_NICKNAME = "관리자"
        /** decided_by 에 남는 값. 사람이 아니라 설정이 정했다는 표시 */
        const val BY = "bootstrap"
    }
}
