package com.my_dream.server.club

import com.my_dream.server.domain.ClubEventReminderRepository
import com.my_dream.server.domain.ClubMember
import com.my_dream.server.domain.ClubMemberRepository
import com.my_dream.server.domain.MemberRole
import com.my_dream.server.domain.MemberStatus
import com.my_dream.server.notify.PushResult
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals

/** 전날 알림 (§4.10 · D34) — 5분마다 돌아도 한 사람에게 한 번, 실패는 다시, 기기 없음은 기록 안 함 */
@DataJpaTest
@Import(ClubEventService::class, ClubReminderJob::class, ClubReminderJobTest.Config::class)
class ClubReminderJobTest @Autowired constructor(
    private val service: ClubEventService,
    private val job: ClubReminderJob,
    private val members: ClubMemberRepository,
    private val reminders: ClubEventReminderRepository,
    private val notifier: RecordingNotifier,
) {

    @TestConfiguration
    class Config {
        @Bean
        fun recordingNotifier() = RecordingNotifier()
    }

    private val today = LocalDate.of(2026, 11, 6)

    @BeforeEach
    fun setUp() {
        listOf("나", "너", "쟤").forEach { members.save(ClubMember(it, "닉$it", MemberRole.MEMBER, MemberStatus.APPROVED, Instant.now())) }
        members.save(ClubMember("대기", "대기", MemberRole.MEMBER, MemberStatus.PENDING, Instant.now()))
        notifier.pushes.clear()
        notifier.results.clear()
    }

    private fun event(date: String? = "2026-11-07", booked: Boolean = true, version: Long? = null) =
        EventInput("모임", date, 1140, null, null, null, booked, null, emptyList(), version, if (date == null) listOf("2026-11-07") else null)

    @Test
    fun `내일 일정의 승인된 참여자에게 한 번 — 다음 바퀴엔 건너뛴다`() {
        val id = service.create("나", event()).id
        service.join("너", id)
        service.create("나", event(date = "2026-11-08"))   // 모레
        service.create("나", event(date = null))           // 날짜 미정
        service.delete("나", service.create("나", event()).id)

        assertEquals(ReminderSummary(2, 0, 0), job.remind(today))
        assertEquals(setOf("나", "너"), notifier.pushes.map { it.userId }.toSet())
        assertEquals("내일 일정이 있어요", notifier.pushes.first().title)
        assertEquals("모임 · 19:00", notifier.pushes.first().body)

        assertEquals(ReminderSummary(0, 0, 0), job.remind(today), "5분 뒤 — 이미 보냈다")
        assertEquals(2, notifier.pushes.size)
    }

    @Test
    fun `실패는 다음 바퀴에 다시, 기기 없음은 기록하지 않는다`() {
        val id = service.create("나", event(booked = false)).id
        service.join("너", id)
        notifier.results["나"] = PushResult(1, 0, 1)
        notifier.results["너"] = PushResult(0, 0, 0)

        assertEquals(ReminderSummary(0, 1, 1), job.remind(today))
        assertEquals(listOf("FAILED"), reminders.findAll().map { it.outcome })
        assertEquals("모임 · 19:00 (아직 예약 전이에요)", notifier.pushes.first().body)

        notifier.results.remove("나")
        assertEquals(ReminderSummary(1, 0, 1), job.remind(today), "실패한 사람은 다시 · 기기 없는 사람은 다시 확인만")
    }

    @Test
    fun `날짜가 바뀌면 새 날짜로 다시 보내고, 시간만 바뀌면 다시 안 보낸다`() {
        val made = service.create("나", event())
        job.remind(today)
        val moved = service.update("나", made.id, EventInput("모임", "2026-11-07", 600, null, null, null, true, null, emptyList(), made.version))
        assertEquals(ReminderSummary(0, 0, 0), job.remind(today), "시간만 바뀜")

        service.update("나", made.id, event(date = "2026-11-08", version = moved.version))
        assertEquals(ReminderSummary(1, 0, 0), job.remind(today.plusDays(1)), "새 날짜의 전날")
    }

    @Test
    fun `승인 안 된 참여자는 건너뛴다`() {
        val id = service.create("나", event()).id
        service.join("너", id)
        members.findByUserId("너")!!.status = MemberStatus.REMOVED
        assertEquals(ReminderSummary(1, 0, 0), job.remind(today))
    }
}
