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
 * 누가 일정에 들어왔다 (참여하기 · 대신 넣기). [joiner] 는 들어간 사람, [actor] 는 그 일을 한 사람(대신 넣기면 넣은 사람).
 * [count] 는 **들어간 순간의** 참여자 수 — 알림이 늦게 나가도 그때의 수를 쓴다. [filled] 면 이 참여로 정원이 처음 찼다(마감 알림)
 */
data class ClubEventJoined(val eventId: Long, val joiner: String, val actor: String, val count: Int, val filled: Boolean)

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
    fun send(change: ClubEventChanged, today: LocalDate = todayKst(), now: Instant = Instant.now()): Int = deliver(change, today, now).size

    /**
     * 참여 한 건 — 정원이 찼으면 마감 알림을 먼저 보내고, **그 마감 알림을 만든 사람이 받았으면** 참여 알림은 건너뛴다
     * (계약 §4.10 "같은 순간 두 통이 가지 않게"). 마감이 10분 제한에 걸려 안 나갔거나 만든 사람이 참여자가 아니면 참여 알림은 간다.
     * @return 참여 알림을 보냈나
     */
    fun joined(j: ClubEventJoined, today: LocalDate = todayKst(), now: Instant = Instant.now()): Boolean {
        val gotFull = if (j.filled) deliver(ClubEventChanged(j.eventId, AlertKind.FULL, j.actor), today, now) else emptyList()
        val event = events.findById(j.eventId).orElse(null)?.takeIf { it.deletedAt == null } ?: return false
        if (event.date?.let { it < today } == true) return false
        val creator = event.createdBy
        // 만든 사람이 스스로 들어갔거나 남을 넣었으면 알 일이다
        if (j.actor == creator || creator in gotFull) return false
        if (members.findByUserId(creator)?.status != MemberStatus.APPROVED) return false

        val nickname = members.findByUserId(j.joiner)?.nickname.orEmpty()
        val (title, body) = ClubPushText.joined(event, segments.findByEventIds(listOf(j.eventId)), nickname, j.count)
        // 횟수 제한은 없다 — 여러 명이 연달아 들어와도 tag 로 기기에서 하나로 겹친다(D50)
        notifier.push(Push(creator, title, body, "#e=${j.eventId}", "ev-join-${j.eventId}"))
        log.info("참여 알림 — 일정 {}", j.eventId)
        return true
    }

    /** @return 보낸 사람들 */
    private fun deliver(change: ClubEventChanged, today: LocalDate, now: Instant): List<String> {
        val event = events.findById(change.eventId).orElse(null)?.takeIf { it.deletedAt == null } ?: return emptyList()
        // 지난 날짜 일정은 기록을 고치려고 누른 것이다 — 알림은 잡음이다
        if (event.date?.let { it < today } == true) return emptyList()
        if (!claim(change.eventId to change.kind, now)) return emptyList()

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
        return to
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
class ClubAlertListener(
    private val alerts: ClubAlerts,
    private val notices: NoticeService,
    private val eventComments: EventCommentService,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val pool = Executors.newSingleThreadExecutor { Thread(it, "club-alerts").apply { isDaemon = true } }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun on(change: ClubEventChanged) {
        pool.execute {
            // 알림이 실패해도 그 요청은 이미 성공했다. 로그만 남긴다
            runCatching { alerts.send(change) }.onFailure { log.error("동호회 알림 실패 — {}", change, it) }
        }
    }

    /** 참여 · 마감 알림(D50 · D34) — 둘을 한 작업으로 보내야 "만든 사람에게 두 통" 을 고를 수 있다 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun on(joined: ClubEventJoined) {
        pool.execute {
            runCatching { alerts.joined(joined) }.onFailure { log.error("참여 알림 실패 — {}", joined, it) }
        }
    }

    /** 공지 알림(D46)도 같은 스레드로 — 저장이 롤백되면 알림도 없고, "공지 올리기" 를 누른 사람은 FCM 을 기다리지 않는다 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun on(posted: NoticePosted) {
        pool.execute {
            runCatching { notices.notifyMembers(posted) }.onFailure { log.error("공지 알림 실패 — 쓴 사람 {}", posted.author, it) }
        }
    }

    /** 일정 댓글 알림(D49)도 같은 스레드로 — 알림이 실패해도 댓글은 이미 저장됐다(계약 "실패해도 POST 는 성공") */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun on(posted: EventCommentPosted) {
        pool.execute {
            runCatching { eventComments.notifyOthers(posted) }.onFailure { log.error("일정 댓글 알림 실패 — 일정 {}", posted.eventId, it) }
        }
    }

    @PreDestroy
    fun stop() {
        pool.shutdown()
    }
}
