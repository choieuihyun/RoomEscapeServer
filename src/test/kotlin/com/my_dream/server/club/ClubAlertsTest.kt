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

    private fun joins() = published.stream(ClubEventJoined::class.java).toList()

    // --- 언제 "바뀌었다" 고 보나 ---

    @Test
    fun `참여마다 한 건, 정원이 처음 찬 순간에만 filled — 넣은 사람이 actor`() {
        // 마감은 ClubEventChanged 가 아니라 참여 사건의 filled 로 온다(D50) — 참여 알림과 한 곳에서 골라 보내려고
        val id = service.create("나", input(capacity = 3)).id
        service.join("너", id)
        service.addParticipant("운영", id, "쟤")
        assertEquals(
            listOf(ClubEventJoined(id, "너", "너", 2, false), ClubEventJoined(id, "쟤", "운영", 3, true)),
            joins(),
            "만들 때 만든 사람이 들어가는 것은 참여가 아니다 · 대신 넣기면 넣은 사람이 한 일이다",
        )
        service.join("쟤", id)   // 이미 있음 — 다시 눌러도 또 안 나간다
        assertEquals(2, joins().size)
        assertTrue(changes().isEmpty())
    }

    @Test
    fun `자리 경쟁에서 진 요청과 정원 줄이기는 알림이 없다`() {
        val made = service.create("나", input(capacity = 3))
        service.join("너", made.id)
        service.update("나", made.id, input(capacity = 2, version = made.version))   // 2/2 가 됐지만 수정으로 — 계약상 없음
        assertEquals("EVENT_FULL", assertFailsWith<ClubError> { service.join("쟤", made.id) }.code)
        assertTrue(changes().isEmpty())
        assertTrue(joins().none { it.filled }, "정원을 줄여 찬 것은 마감이 아니다")
        assertEquals(listOf("너"), joins().map { it.joiner }, "진 요청은 사건도 없다")
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

    // --- 참여 알림 (D50) ---

    @Test
    fun `참여 알림은 만든 사람에게만 — 문구 · 링크 · tag, 정원이 없으면 수만`() {
        val id = service.create("나", input(capacity = null)).id
        service.join("너", id)
        assertTrue(alerts.joined(ClubEventJoined(id, "너", "너", 2, false), today, now))
        val p = notifier.pushes.single()
        assertEquals("나", p.userId)
        assertEquals("닉너 님이 참여했어요", p.title)
        assertEquals("모임 · 11/7(토) · 2명", p.body)
        assertEquals("#e=$id", p.linkFragment)
        assertEquals("ev-join-$id", p.tag)

        // 횟수 제한 없음 — 바로 또 와도 보낸다(기기에서 겹친다)
        assertTrue(alerts.joined(ClubEventJoined(id, "쟤", "쟤", 3, false), today, now))
        assertEquals("닉쟤 님이 참여했어요", notifier.pushes.last().title)
    }

    @Test
    fun `대신 넣기면 들어간 사람 이름, 넣은 사람이 만든 사람이면 안 보낸다`() {
        val id = service.create("나", input(capacity = 4)).id
        assertTrue(alerts.joined(ClubEventJoined(id, "쟤", "운영", 2, false), today, now))
        assertEquals("닉쟤 님이 참여했어요", notifier.pushes.single().title)
        assertEquals("모임 · 11/7(토) · 2/4명", notifier.pushes.single().body)
        notifier.pushes.clear()
        assertTrue(!alerts.joined(ClubEventJoined(id, "너", "나", 3, false), today, now), "만든 사람이 넣었다")
        assertTrue(!alerts.joined(ClubEventJoined(id, "나", "나", 1, false), today, now), "만든 사람 본인")
        assertTrue(notifier.pushes.isEmpty())
    }

    @Test
    fun `만든 사람이 승인 멤버가 아니거나, 지난·지운 일정이면 없다 — 날짜 미정은 보낸다`() {
        val removed = service.create("나", input()).id
        members.findByUserId("나")!!.status = MemberStatus.REMOVED
        assertTrue(!alerts.joined(ClubEventJoined(removed, "너", "너", 2, false), today, now))
        members.findByUserId("나")!!.status = MemberStatus.APPROVED

        val past = service.create("나", input(date = "2026-10-31")).id
        val gone = service.create("나", input()).id.also { service.delete("나", it) }
        assertTrue(!alerts.joined(ClubEventJoined(past, "너", "너", 2, false), today, now))
        assertTrue(!alerts.joined(ClubEventJoined(gone, "너", "너", 2, false), today, now))
        assertTrue(notifier.pushes.isEmpty())

        val open = service.create("나", input(date = null)).id
        assertTrue(alerts.joined(ClubEventJoined(open, "너", "너", 2, false), today, now))
        assertEquals("모임 · 날짜 미정 · 2/3명", notifier.pushes.single().body)
    }

    @Test
    fun `그 참여로 찼고 만든 사람이 마감을 받으면 참여 알림은 없다 — 두 통 방지`() {
        val id = service.create("나", input(capacity = 2)).id
        service.join("너", id)
        assertTrue(!alerts.joined(ClubEventJoined(id, "너", "너", 2, true), today, now))
        assertEquals(listOf("나" to "일정 자리가 다 찼어요"), notifier.pushes.map { it.userId to it.title }, "만든 사람은 마감 한 통만")
    }

    @Test
    fun `찼어도 만든 사람이 참여자가 아니면 참여 알림이 간다 — 마감이 10분 제한에 걸려도`() {
        val a = service.create("나", input(capacity = 2)).id
        service.leave("나", a)
        listOf("너", "쟤").forEach { service.join(it, a) }
        assertTrue(alerts.joined(ClubEventJoined(a, "쟤", "쟤", 2, true), today, now))
        assertEquals(setOf("너" to "일정 자리가 다 찼어요", "나" to "닉쟤 님이 참여했어요"), notifier.pushes.map { it.userId to it.title }.toSet())

        // 만든 사람이 참여자여도, 마감이 10분 안에 이미 나가 이번엔 안 나가면 참여 알림은 간다
        val b = service.create("나", input(capacity = 2)).id
        service.join("너", b)
        alerts.send(ClubEventChanged(b, AlertKind.FULL, "너"), today, now)
        notifier.pushes.clear()
        assertTrue(alerts.joined(ClubEventJoined(b, "너", "너", 2, true), today, now.plusSeconds(60)))
        assertEquals(listOf("나" to "닉너 님이 참여했어요"), notifier.pushes.map { it.userId to it.title })
    }
}
