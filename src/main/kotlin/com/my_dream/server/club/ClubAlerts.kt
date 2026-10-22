package com.my_dream.server.club

import com.my_dream.server.domain.ClubEventParticipantRepository
import com.my_dream.server.domain.ClubEventRepository
import com.my_dream.server.domain.ClubEventSegmentRepository
import com.my_dream.server.domain.ClubMemberRepository
import com.my_dream.server.domain.MemberStatus
import com.my_dream.server.notify.Notifier
import com.my_dream.server.notify.Push
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

enum class AlertKind { FULL, BOOKED }

/**
 * "일정이 바뀌었다" — [ClubEventService] 가 **잠근 일정 행 안에서** 판정하고 알린다 (D34).
 * [actor] 는 그 일을 한 사람 — 그 사람에게는 안 보낸다(대신 넣기면 넣은 사람)
 */
data class ClubEventChanged(val eventId: Long, val kind: AlertKind, val actor: String)

/**
 * 마감 · 예약 완료 알림 (계약 §4.10 · 아키텍처 D34).
 *
 * 같은 일정 같은 종류는 **10분에 한 번** — 마감↔취소가 반복되면 알림이 쏟아진다. 기억은 메모리라
 * 재시작하면 한 번 더 갈 수 있다(계약이 받아들였다).
 */
@Component
class ClubAlerts(
    private val events: ClubEventRepository,
    private val segments: ClubEventSegmentRepository,
    private val participants: ClubEventParticipantRepository,
    private val members: ClubMemberRepository,
    private val notifier: Notifier,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val lastSent = ConcurrentHashMap<Pair<Long, AlertKind>, Instant>()

    /** @return 보낸 사람 수 */
    fun send(change: ClubEventChanged, today: LocalDate = todayKst(), now: Instant = Instant.now()): Int {
        val event = events.findById(change.eventId).orElse(null)?.takeIf { it.deletedAt == null } ?: return 0
        // 지난 날짜 일정은 기록을 고치려고 누른 것이다 — 알림은 잡음이다
        if (event.date?.let { it < today } == true) return 0
        if (!claim(change.eventId to change.kind, now)) return 0

        val segs = segments.findByEventIds(listOf(change.eventId))
        val uids = participants.findByEventIds(listOf(change.eventId)).map { it.userId }
        val approved = members.findByUserIdIn(uids.toSet()).filter { it.status == MemberStatus.APPROVED }.map { it.userId }.toSet()
        val (title, body) = when (change.kind) {
            AlertKind.FULL -> ClubPushText.full(event, segs, requireNotNull(event.capacity))
            AlertKind.BOOKED -> ClubPushText.booked(event, segs)
        }
        val to = uids.filter { it != change.actor && it in approved }
        // 기기가 없는 사람은 FcmNotifier 가 조용히 건너뛴다 (NO_ADDRESS)
        to.forEach { notifier.push(Push(it, title, body, "#e=${change.eventId}")) }
        log.info("동호회 알림 {} — 일정 {} · {}명", change.kind, change.eventId, to.size)
        return to.size
    }

    /** 10분 안에 같은 알림을 보냈으면 false. 비교와 기록을 한 번에 한다 — 동시에 둘이 와도 하나만 통과한다 */
    private fun claim(key: Pair<Long, AlertKind>, now: Instant): Boolean {
        var ok = false
        lastSent.compute(key) { _, prev ->
            if (prev == null || Duration.between(prev, now) >= QUIET) {
                ok = true
                now
            } else {
                prev
            }
        }
        return ok
    }

    companion object {
        val QUIET: Duration = Duration.ofMinutes(10)
    }
}

/**
 * **커밋이 끝난 뒤에**, 요청 스레드가 아닌 곳에서 보낸다 (D34).
 *
 * 커밋 전에 보내면 롤백된 요청(자리 경쟁에서 진 참여)도 "다 찼어요" 를 보낸다. 요청 스레드에서 보내면
 * 버튼 누른 사람이 FCM 왕복을 기다린다. `@EnableAsync` 를 전역으로 켜지 않고 스레드 하나를 따로 둔다 —
 * 전역 설정은 앱 전체의 동작을 바꾼다
 */
@Component
class ClubAlertListener(private val alerts: ClubAlerts, private val notices: NoticeService) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val pool = Executors.newSingleThreadExecutor { Thread(it, "club-alerts").apply { isDaemon = true } }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun on(change: ClubEventChanged) {
        pool.execute {
            // 알림이 실패해도 그 요청은 이미 성공했다. 로그만 남긴다
            runCatching { alerts.send(change) }.onFailure { log.error("동호회 알림 실패 — {}", change, it) }
        }
    }

    /** 공지 알림(D46)도 같은 스레드로 — 저장이 롤백되면 알림도 없고, "공지 올리기" 를 누른 사람은 FCM 을 기다리지 않는다 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun on(posted: NoticePosted) {
        pool.execute {
            runCatching { notices.notifyMembers(posted) }.onFailure { log.error("공지 알림 실패 — 쓴 사람 {}", posted.author, it) }
        }
    }

    @PreDestroy
    fun stop() {
        pool.shutdown()
    }
}
