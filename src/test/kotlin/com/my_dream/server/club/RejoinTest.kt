package com.my_dream.server.club

import com.my_dream.server.domain.ClubMember
import com.my_dream.server.domain.ClubMemberRepository
import com.my_dream.server.domain.MemberRole
import com.my_dream.server.domain.MemberStatus
import com.my_dream.server.domain.PlayedSource
import com.my_dream.server.domain.PlayedTheme
import com.my_dream.server.domain.PlayedThemeRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 내보냄 → 다시 가입 신청 → 운영진 승인 한 바퀴 (2026-10-20 사용자 결정 · 아키텍처 D28 정정).
 * 내보낼 때 아무것도 안 지우므로, 다시 승인되면 **남아 있던 것이 그대로 살아나야 한다**.
 */
@DataJpaTest
@Import(MembershipService::class, ClubEventService::class, RankingService::class)
class RejoinTest @Autowired constructor(
    private val membership: MembershipService,
    private val events: ClubEventService,
    private val rankings: RankingService,
    private val members: ClubMemberRepository,
    private val played: PlayedThemeRepository,
) {

    private val today = LocalDate.of(2026, 10, 20)

    @Test
    fun `다시 승인되면 새 승인일, 만든 일정은 다시 내 것, 지난 랭킹 기록도 다시 보인다`() {
        members.save(ClubMember("운영", "닉운영", MemberRole.ADMIN, MemberStatus.APPROVED, Instant.now()))
        val old = Instant.parse("2026-09-01T00:00:00Z")
        members.save(ClubMember("u1", "철수", MemberRole.MEMBER, MemberStatus.APPROVED, old, approvedAt = old))
        val made = events.create("u1", EventInput("보드게임처럼", "2026-11-07", null, null, null, null, false, null, emptyList(), null, null))
        played.save(PlayedTheme("u1", null, "비밀의 화원", null, "비밀의화원", LocalDate.of(2026, 9, 5), PlayedSource.MANUAL, null, Instant.now()))

        membership.remove("운영", "u1")
        assertFalse(events.get("운영", made.id).mine)
        assertTrue(rankings.rankings("운영", "2026-09", "2026-09", today).single().ranks.isEmpty(), "내보낸 동안은 지난 달에서도 빠진다")

        assertEquals("PENDING", membership.join("u1", "철수").status)
        membership.approve("운영", "u1")

        val approvedAt = assertNotNull(members.findByUserId("u1")!!.approvedAt)
        assertTrue(approvedAt > old, "승인일은 승인할 때마다 찍는다 (D35)")
        assertTrue(events.get("u1", made.id).mine, "uid 가 같으므로 만든 일정이 다시 내 것이다")
        assertEquals(listOf("u1"), rankings.rankings("운영", "2026-09", "2026-09", today).single().ranks.map { it.uid })
    }
}
