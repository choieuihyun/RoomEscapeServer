package com.my_dream.server.club

import com.my_dream.server.domain.ClubEventAvailabilityRepository
import com.my_dream.server.domain.ClubEventCandidateRepository
import com.my_dream.server.domain.ClubEventParticipantRepository
import com.my_dream.server.domain.ClubEventRepository
import com.my_dream.server.domain.ClubEventSegmentRepository
import com.my_dream.server.domain.ClubMember
import com.my_dream.server.domain.ClubMemberRepository
import com.my_dream.server.domain.MemberRole
import com.my_dream.server.domain.MemberStatus
import com.my_dream.server.domain.PlayedThemeRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.context.annotation.Import
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * 마감 알림이 **커밋 뒤에** 나가는지 — 테스트 트랜잭션 없이 진짜로 커밋한다 (D34).
 * 롤백된 요청(자리 경쟁에서 진 참여)은 아무것도 안 보내야 한다.
 */
@SpringBootTest
@Import(ClubAlertsCommitTest.Config::class)
class ClubAlertsCommitTest @Autowired constructor(
    private val service: ClubEventService,
    private val notifier: RecordingNotifier,
    private val members: ClubMemberRepository,
    private val events: ClubEventRepository,
    private val segments: ClubEventSegmentRepository,
    private val participants: ClubEventParticipantRepository,
    private val candidates: ClubEventCandidateRepository,
    private val answers: ClubEventAvailabilityRepository,
    private val played: PlayedThemeRepository,
) {

    @TestConfiguration
    class Config {
        @Bean
        @Primary
        fun recordingNotifier() = RecordingNotifier()
    }

    @BeforeEach
    fun setUp() {
        played.deleteAll(); answers.deleteAll(); candidates.deleteAll(); participants.deleteAll(); segments.deleteAll(); events.deleteAll()
        members.deleteAll()
        listOf("나", "너", "쟤").forEach { members.save(ClubMember(it, "닉$it", MemberRole.MEMBER, MemberStatus.APPROVED, Instant.now())) }
        notifier.pushes.clear()
    }

    private fun awaitPushes(n: Int): List<String> {
        repeat(50) { if (notifier.pushes.size >= n) return notifier.pushes.map { it.userId }; Thread.sleep(50) }
        return notifier.pushes.map { it.userId }
    }

    @Test
    fun `커밋된 마감은 따로 나가고, 롤백된 참여는 아무것도 안 보낸다`() {
        val date = LocalDate.now(KST).plusDays(7).toString()
        val id = service.create("나", EventInput("모임", date, null, null, null, 2, false, null, emptyList())).id
        service.join("너", id)
        assertEquals(listOf("나"), awaitPushes(1), "찬 사람(너) 말고 나에게")

        assertEquals("EVENT_FULL", assertFailsWith<ClubError> { service.join("쟤", id) }.code)
        Thread.sleep(300)
        assertEquals(1, notifier.pushes.size)
    }
}
