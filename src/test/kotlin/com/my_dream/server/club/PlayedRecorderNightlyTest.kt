package com.my_dream.server.club

import com.my_dream.server.domain.ClubMember
import com.my_dream.server.domain.ClubMemberRepository
import com.my_dream.server.domain.MemberRole
import com.my_dream.server.domain.MemberStatus
import com.my_dream.server.domain.PlayedThemeRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * 04:30 작업을 **스케줄러가 부르는 그대로** — 바깥 트랜잭션 없이 `nightly()` 로 — 부른다.
 *
 * 다른 테스트는 `@DataJpaTest` 의 테스트 트랜잭션 안에서 `record()` 를 바로 불러서, `nightly()` 가 `record()` 를
 * 자기 호출해 트랜잭션이 빠지는 문제를 못 봤다 (2026-10-24 · 25 운영 오류). 그래서 이 클래스만 트랜잭션을 끈다
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(PlayedRecorder::class, ClubEventService::class, WishService::class)
class PlayedRecorderNightlyTest @Autowired constructor(
    private val recorder: PlayedRecorder,
    private val events: ClubEventService,
    private val members: ClubMemberRepository,
    private val played: PlayedThemeRepository,
    private val jdbc: JdbcTemplate,
) {

    // 트랜잭션이 없어 되돌려지지 않는다 — 직접 지운다
    @AfterEach
    fun cleanUp() {
        listOf("played_theme", "club_event_segment", "club_event_participant", "club_event_candidate", "club_event", "club_member")
            .forEach { jdbc.update("DELETE FROM $it") }
    }

    @Test
    fun `바깥 트랜잭션 없이 불러도 기록하고 일정에 표시를 단다`() {
        members.save(ClubMember("나", "닉나", MemberRole.MEMBER, MemberStatus.APPROVED, Instant.now()))
        val e = events.create(
            "나",
            EventInput("모임", todayKst().plusDays(3).toString(), null, null, null, null, true, null,
                listOf(SegmentInput(themeName = "목격자", branchName = "건대점", startMin = 600)), null, null),
        )
        // 지난 날짜로는 만들 수 없을 수 있어 만든 뒤 옮긴다
        jdbc.update("UPDATE club_event SET event_date = ? WHERE id = ?", todayKst().minusDays(1), e.id)

        recorder.nightly()

        assertEquals(listOf("목격자"), played.findByUserId("나").map { it.themeName })
        assertNotNull(jdbc.queryForMap("SELECT played_recorded_at FROM club_event WHERE id = ?", e.id)["played_recorded_at"])
    }
}
