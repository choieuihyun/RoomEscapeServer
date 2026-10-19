package com.my_dream.server.club

import com.my_dream.server.domain.ClubEventParticipantRepository
import com.my_dream.server.domain.ClubEventReminder
import com.my_dream.server.domain.ClubEventReminderRepository
import com.my_dream.server.domain.ClubEventRepository
import com.my_dream.server.domain.ClubEventSegmentRepository
import com.my_dream.server.domain.ClubMemberRepository
import com.my_dream.server.domain.MemberStatus
import com.my_dream.server.notify.Delivery
import com.my_dream.server.notify.Notifier
import com.my_dream.server.notify.Push
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate

data class ReminderSummary(val sent: Int, val failed: Int, val noDevice: Int)

/**
 * 전날 알림 (계약 §4.10 · 아키텍처 D34).
 *
 * **20시에 한 번이 아니라 20:00~21:55 사이 5분마다** 돈다 — 20시 정각에 자동 배포 재시작이 겹치면
 * 하루치가 통째로 빠진다. 중복은 기록(`club_event_reminder`)이 막는다: SENT 가 있는 사람은 건너뛴다.
 * 그 결과 22시 이후에 만든 내일 일정은 알림이 없다(계약).
 */
@Component
class ClubReminderJob(
    private val events: ClubEventRepository,
    private val segments: ClubEventSegmentRepository,
    private val participants: ClubEventParticipantRepository,
    private val members: ClubMemberRepository,
    private val reminders: ClubEventReminderRepository,
    private val notifier: Notifier,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** **zone 을 못 박는다** — VM 은 UTC 다 (배포.md 백업 크론 사고) */
    @Scheduled(cron = "0 */5 20-21 * * *", zone = "Asia/Seoul")
    fun tick() {
        val s = remind(todayKst())
        if (s.sent + s.failed > 0) log.info("전날 알림 — 보냄 {} · 실패 {} · 기기 없음 {}", s.sent, s.failed, s.noDevice)
    }

    /** 트랜잭션으로 묶지 않는다 — 사람마다 FCM 왕복이 있어 DB 연결을 그동안 붙잡지 않게. 기록은 한 줄씩 저장한다 */
    fun remind(today: LocalDate): ReminderSummary {
        val tomorrow = today.plusDays(1)
        // 날짜 미정(null)은 여기서 저절로 빠진다 (D32)
        val targets = events.findByDateAndDeletedAtIsNull(tomorrow)
        if (targets.isEmpty()) return ReminderSummary(0, 0, 0)
        val ids = targets.map { requireNotNull(it.id) }
        val done = reminders.findSentFor(tomorrow).map { it.eventId to it.userId }.toSet()
        val segsByEvent = segments.findByEventIds(ids).groupBy { requireNotNull(it.event.id) }
        val peopleByEvent = participants.findByEventIds(ids).groupBy { requireNotNull(it.event.id) }
        val approved = members.findByUserIdIn(peopleByEvent.values.flatten().map { it.userId }.toSet())
            .filter { it.status == MemberStatus.APPROVED }.map { it.userId }.toSet()

        var sent = 0
        var failed = 0
        var noDevice = 0
        for (event in targets) {
            val id = requireNotNull(event.id)
            val (title, body) = ClubPushText.reminder(event, segsByEvent[id].orEmpty())
            for (uid in peopleByEvent[id].orEmpty().map { it.userId }) {
                if (uid !in approved || (id to uid) in done) continue
                when (notifier.push(Push(uid, title, body, "#e=$id")).delivery) {
                    Delivery.DELIVERED -> sent += record(id, uid, tomorrow, ClubEventReminder.SENT)
                    // 실패는 남기되 "보냄" 으로 세지 않는다 — 5분 뒤 다시 시도한다
                    Delivery.FAILED -> failed += record(id, uid, tomorrow, ClubEventReminder.FAILED)
                    // 기기가 없는 것은 시도가 아니라 설정 상태다. 기록하지 않는다 (D17)
                    Delivery.NO_ADDRESS -> noDevice++
                }
            }
        }
        return ReminderSummary(sent, failed, noDevice)
    }

    private fun record(eventId: Long, uid: String, date: LocalDate, outcome: String): Int =
        try {
            reminders.save(ClubEventReminder(eventId, uid, date, outcome, Instant.now()))
            1
        } catch (e: DataIntegrityViolationException) {
            // 운영 DB 의 부분 UNIQUE(SENT) 에 걸렸다 — 이미 누가 기록했다. 알림은 한 번 더 갔을 수 있지만 기록은 하나다
            log.warn("전날 알림 기록이 이미 있다 — 일정 {} · {}", eventId, uid)
            0
        }
}
