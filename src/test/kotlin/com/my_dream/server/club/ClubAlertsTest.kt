package com.my_dream.server.club

import com.my_dream.server.domain.ClubMember
import com.my_dream.server.domain.ClubMemberRepository
import com.my_dream.server.domain.MemberRole
import com.my_dream.server.domain.MemberStatus
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 마감 · 예약 완료 알림 (§4.10 · D34). "바뀌었다" 판정(서비스)과 보내기([ClubAlerts])를 나눠 본다.
 * 커밋 뒤에 따로 나가는지는 `ClubAlertsCommitTest`(진짜 트랜잭션)가 본다.
 */
@DataJpaTest
@RecordApplicationEvents
@Import(ClubEventService::class, ClubAlerts::class, ClubAlertsTest.Config::class)
class ClubAlertsTest @Autowired constructor(
    private val service: ClubEventService,
    private val alerts: ClubAlerts,
    private val members: ClubMemberRepository,
    private val notifier: RecordingNotifier,
) {

    /** 테스트마다 새로 기록된다 — 생성자로는 못 받는다(테스트 하나의 범위라서) */
    @Autowired
    lateinit var published: ApplicationEvents

    @TestConfiguration
    class Config {
        @Bean
        fun recordingNotifier() = RecordingNotifier()
    }

    private val today = LocalDate.of(2026, 11, 1)
    private val now = Instant.parse("2026-11-01T03:00:00Z")

    @BeforeEach
    fun setUp() {
        listOf("나", "너", "쟤", "운영").forEach {
            members.save(ClubMember(it, "닉$it", if (it == "운영") MemberRole.ADMIN else MemberRole.MEMBER, MemberStatus.APPROVED, Instant.now()))
        }
        members.save(ClubMember("내보냄", "내보냄", MemberRole.MEMBER, MemberStatus.REMOVED, Instant.now()))
        notifier.pushes.clear()
    }

    private fun input(capacity: Int? = 3, booked: Boolean = false, date: String? = "2026-11-07", version: Long? = null, title: String = "모임") =
        EventInput(title, date, null, null, null, capacity, booked, null, emptyList(), version, if (date == null) listOf("2026-11-07") else null)

    private fun changes() = published.stream(ClubEventChanged::class.java).toList()

    // --- 언제 "바뀌었다" 고 보나 ---

    @Test
    fun `정원이 처음 찬 순간에만 마감 — 넣은 사람이 actor`() {
        val id = service.create("나", input(capacity = 3)).id
        service.join("너", id)
        assertTrue(changes().isEmpty(), "2/3 — 아직")
        service.addParticipant("운영", id, "쟤")
        assertEquals(listOf(ClubEventChanged(id, AlertKind.FULL, "운영")), changes(), "대신 넣기면 넣은 사람이 한 일이다")
        service.join("쟤", id)   // 이미 있음 — 다시 눌러도 또 안 나간다
        assertEquals(1, changes().size)
    }

    @Test
    fun `자리 경쟁에서 진 요청과 정원 줄이기는 알림이 없다`() {
        val made = service.create("나", input(capacity = 3))
        service.join("너", made.id)
        service.update("나", made.id, input(capacity = 2, version = made.version))   // 2/2 가 됐지만 수정으로 — 계약상 없음
        assertEquals("EVENT_FULL", assertFailsWith<ClubError> { service.join("쟤", made.id) }.code)
        assertTrue(changes().isEmpty())
    }

    @Test
    fun `예약 완료는 false 에서 true 로 바뀔 때만 — booking 이든 수정이든, 충돌한 수정은 없다`() {
        val a = service.create("나", input())
        service.setBooking("너".also { service.join(it, a.id) }, a.id, true)
        service.setBooking("너", a.id, true)
        service.setBooking("너", a.id, false)
        val b = service.create("나", input())
        assertEquals("EVENT_CONFLICT", assertFailsWith<ClubError> { service.update("나", b.id, input(booked = true, version = b.version + 5)) }.code)
        service.update("나", b.id, input(booked = true, version = b.version))
        assertEquals(listOf(ClubEventChanged(a.id, AlertKind.BOOKED, "너"), ClubEventChanged(b.id, AlertKind.BOOKED, "나")), changes())
    }

    // --- 보내기 ---

    @Test
    fun `한 사람은 빼고, 승인 멤버인 참여자에게만`() {
        val id = service.create("나", input(capacity = 4)).id
        listOf("너", "쟤").forEach { service.join(it, id) }
        service.addParticipant("나", id, "운영")
        members.findByUserId("쟤")!!.status = MemberStatus.REMOVED

        assertEquals(2, alerts.send(ClubEventChanged(id, AlertKind.FULL, "운영"), today, now))
        assertEquals(setOf("나", "너"), notifier.pushes.map { it.userId }.toSet())
        assertEquals("일정 자리가 다 찼어요", notifier.pushes.first().title)
        assertEquals("모임 · 11/7(토) · 4/4명", notifier.pushes.first().body)
        assertEquals("#e=$id", notifier.pushes.first().linkFragment)
    }

    @Test
    fun `같은 일정 같은 종류는 10분에 한 번, 종류가 다르면 따로`() {
        val id = service.create("나", input()).id
        service.join("너", id)
        val e = ClubEventChanged(id, AlertKind.BOOKED, "나")
        assertEquals(1, alerts.send(e, today, now))
        assertEquals(0, alerts.send(e, today, now.plusSeconds(599)))
        assertEquals(1, alerts.send(ClubEventChanged(id, AlertKind.FULL, "나"), today, now.plusSeconds(60)))
        assertEquals(1, alerts.send(e, today, now.plusSeconds(600)))
    }

    @Test
    fun `지난 날짜·지운 일정은 안 보내고, 날짜 미정은 보낸다`() {
        val past = service.create("나", input(date = "2026-10-31")).id
        val gone = service.create("나", input()).id
        val open = service.create("나", input(date = null)).id
        listOf(past, gone, open).forEach { service.join("너", it) }
        service.delete("나", gone)

        assertEquals(0, alerts.send(ClubEventChanged(past, AlertKind.BOOKED, "나"), today, now))
        assertEquals(0, alerts.send(ClubEventChanged(gone, AlertKind.BOOKED, "나"), today, now))
        assertEquals(1, alerts.send(ClubEventChanged(open, AlertKind.BOOKED, "나"), today, now))
        assertEquals("모임 · 날짜 미정", notifier.pushes.single().body)
    }
}
