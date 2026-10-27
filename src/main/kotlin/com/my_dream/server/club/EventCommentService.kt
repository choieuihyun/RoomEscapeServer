package com.my_dream.server.club

import com.my_dream.server.domain.ClubEvent
import com.my_dream.server.domain.ClubEventComment
import com.my_dream.server.domain.ClubEventCommentRepository
import com.my_dream.server.domain.ClubEventParticipantRepository
import com.my_dream.server.domain.ClubEventRepository
import com.my_dream.server.domain.ClubEventSegmentRepository
import com.my_dream.server.domain.ClubMember
import com.my_dream.server.domain.ClubMemberRepository
import com.my_dream.server.domain.MemberRole
import com.my_dream.server.domain.MemberStatus
import com.my_dream.server.notify.Notifier
import com.my_dream.server.notify.Push
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

// --- 계약 (Floduler `작업명세서.md` §4.16). 모양을 바꾸려면 그 문서부터 ---

data class EventCommentInput(val text: String? = null)

/** 의견 댓글([FeedbackCommentDto])과 같은 모양 + [eventId] */
data class EventCommentDto(
    val id: Long,
    val eventId: Long,
    val text: String,
    val author: PersonDto,
    val admin: Boolean,
    val createdAt: Instant,
    val mine: Boolean,
    val canDelete: Boolean,
)

/** 댓글이 저장됐다 — 알림은 커밋 뒤 [ClubAlertListener] 가 보낸다. 글은 메모리로만 지나간다(로그에 안 남긴다) */
data class EventCommentPosted(val eventId: Long, val author: String, val authorNickname: String, val text: String)

/**
 * 일정 댓글 (아키텍처 D49). 승인 멤버 누구나 — 참여하지 않아도 쓴다. 지우기는 쓴 사람·운영진.
 *
 * **알림을 보낸다**(사용자 결정 "댓글에 알림 받아야해"). 의견 게시판(D47)은 안 보낸다 — 일정 댓글은 "그 날 같이 가는 사람들"
 * 끼리의 이야기라 놓치면 안 되고, 의견은 운영진이 화면에서 본다. 같은 일정의 알림은 기기에서 하나로 겹치게 해서([Push.tag])
 * 서버 쪽 횟수 제한은 두지 않는다
 */
@Service
class EventCommentService(
    private val events: ClubEventRepository,
    private val segments: ClubEventSegmentRepository,
    private val participants: ClubEventParticipantRepository,
    private val comments: ClubEventCommentRepository,
    private val members: ClubMemberRepository,
    private val notifier: Notifier,
    private val publisher: ApplicationEventPublisher,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** 오래된 순 */
    @Transactional(readOnly = true)
    fun list(uid: String, eventId: Long): List<EventCommentDto> {
        val viewer = members.requireApproved(uid)
        live(eventId)
        val rows = comments.findByEventIdOrderById(eventId)
        val names = nicknames(rows.map { it.userId })
        return rows.map { it.toDto(viewer, names) }
    }

    /** 지난 날짜 일정에도 쓴다(정산·후기 이야기) — 알림도 간다 */
    @Transactional
    fun create(uid: String, eventId: Long, input: EventCommentInput, now: Instant = Instant.now()): EventCommentDto {
        val viewer = members.requireApproved(uid)
        val text = boundedText(input.text, MAX_LETTERS, MAX_LINES, ClubError::badEventComment)
        live(eventId)
        val row = comments.save(ClubEventComment(eventId, uid, text, viewer.isActiveAdmin(), now))
        publisher.publishEvent(EventCommentPosted(eventId, uid, viewer.nickname, text))
        return row.toDto(viewer, mapOf(uid to viewer.nickname))
    }

    /** 지운 일정의 댓글은 일정과 함께 지워져 여기 안 온다 — 그래서 일정이 살아 있는지는 따로 안 본다 */
    @Transactional
    fun delete(uid: String, commentId: Long): DeletedDto {
        val viewer = members.requireApproved(uid)
        val row = comments.findById(commentId).orElse(null) ?: throw ClubError.noSuchComment()
        if (row.userId != uid && !viewer.isActiveAdmin()) throw ClubError.notAuthor()
        comments.delete(row)
        return DeletedDto(commentId, true)
    }

    /**
     * 받는 사람 = 승인된 참여자 + 만든 사람 + 이 일정에 댓글을 쓴 적 있는 사람 — 쓴 사람 빼고, 겹치지 않게, **전부 승인 멤버만**
     * (만든 사람도 — 내보낸 사람에게는 알림이 안 간다. "내보내면 빈자리 알림도 지움"(V13 · 사용자 결정 2026-10-20) 과 같은 기준).
     * 기기가 없는 사람은 FcmNotifier 가 조용히 건너뛴다.
     * 🔒 댓글 글은 로그에 안 남긴다 — 사람 수만
     * @return 보낸 사람 수
     */
    @Transactional(readOnly = true)
    fun notifyOthers(posted: EventCommentPosted): Int {
        // 커밋과 보내기 사이에 일정이 지워졌으면 보내지 않는다 — 눌러도 "없는 일정" 이 열린다
        val event = events.findById(posted.eventId).orElse(null)?.takeIf { it.deletedAt == null } ?: return 0
        val candidates = participants.findByEventIds(listOf(posted.eventId)).map { it.userId } +
            event.createdBy +
            comments.findByEventIdOrderById(posted.eventId).map { it.userId }
        val approved = members.findByUserIdIn(candidates.toSet()).filter { it.status == MemberStatus.APPROVED }.map { it.userId }.toSet()
        val to = candidates.distinct().filter { it != posted.author && it in approved }

        val (title, body) = ClubPushText.comment(event, segments.findByEventIds(listOf(posted.eventId)), posted.authorNickname, posted.text)
        to.forEach { notifier.push(Push(it, title, body, "#e=${posted.eventId}", "ev-cmt-${posted.eventId}")) }
        log.info("일정 댓글 알림 — 일정 {} · {}명", posted.eventId, to.size)
        return to.size
    }

    private fun live(eventId: Long): ClubEvent =
        events.findById(eventId).orElse(null)?.takeIf { it.deletedAt == null } ?: throw ClubError.noSuchEvent()

    private fun ClubEventComment.toDto(viewer: ClubMember, names: Map<String, String>) = EventCommentDto(
        id = requireNotNull(id),
        eventId = eventId,
        text = text,
        author = PersonDto(userId, names[userId].orEmpty()),
        admin = admin,
        createdAt = createdAt,
        mine = userId == viewer.userId,
        canDelete = userId == viewer.userId || viewer.isActiveAdmin(),
    )

    /** 내보낸 사람도 멤버 행은 남는다(D28) — 계약의 "저장된 닉네임" 이 그것이다 */
    private fun nicknames(uids: Collection<String>): Map<String, String> =
        if (uids.isEmpty()) emptyMap() else members.findByUserIdIn(uids.toSet()).associate { it.userId to it.nickname }

    private fun ClubMember.isActiveAdmin() = role == MemberRole.ADMIN && status == MemberStatus.APPROVED

    companion object {
        const val MAX_LETTERS = 300
        const val MAX_LINES = 10
    }
}
