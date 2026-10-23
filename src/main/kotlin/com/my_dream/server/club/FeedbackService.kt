package com.my_dream.server.club

import com.my_dream.server.domain.ClubFeedback
import com.my_dream.server.domain.ClubFeedbackComment
import com.my_dream.server.domain.ClubFeedbackCommentRepository
import com.my_dream.server.domain.ClubFeedbackLike
import com.my_dream.server.domain.ClubFeedbackLikeRepository
import com.my_dream.server.domain.ClubFeedbackRepository
import com.my_dream.server.domain.ClubMember
import com.my_dream.server.domain.ClubMemberRepository
import com.my_dream.server.domain.FeedbackStatus
import com.my_dream.server.domain.MemberRole
import com.my_dream.server.domain.MemberStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

// --- 계약 (Floduler `작업명세서.md` §4.15). 모양을 바꾸려면 그 문서부터 ---

/** 글·댓글 본문. **null 을 받는다** — 빠진 글을 여기서 걸러야 계약의 `BAD_FEEDBACK`·`BAD_COMMENT` 가 나간다 */
data class FeedbackTextInput(val text: String? = null)

data class FeedbackLikeInput(val liked: Boolean? = null)

/** 상태는 글자로 받는다 — enum 칸이면 모르는 값이 잭슨에서 터져 `BAD_STATUS` 문구가 안 나간다 */
data class FeedbackStatusInput(val status: String? = null)

data class FeedbackPersonDto(val uid: String, val nickname: String)

data class FeedbackDto(
    val id: Long,
    val text: String,
    val status: FeedbackStatus,
    val author: FeedbackPersonDto,
    val createdAt: Instant,
    val updatedAt: Instant,
    val statusAt: Instant?,
    val statusBy: FeedbackPersonDto?,
    val likes: Int,
    val liked: Boolean,
    val comments: Int,
    val mine: Boolean,
    val canDelete: Boolean,
)

data class FeedbackCommentDto(
    val id: Long,
    val feedbackId: Long,
    val text: String,
    val author: FeedbackPersonDto,
    val admin: Boolean,
    val createdAt: Instant,
    val mine: Boolean,
    val canDelete: Boolean,
)

/**
 * 의견 게시판 (아키텍처 D47). 전부 승인 멤버만. 고치기는 쓴 사람만, 지우기는 쓴 사람·운영진, 상태는 운영진.
 * 알림은 보내지 않는다(사용자 결정 — 화면에서만).
 *
 * 공감 수·댓글 수는 **그 글들의 행을 읽어 Kotlin 이 센다.** 목록이 200개까지고 멤버가 수십 명이라 행이 많아야 수천(추정)이다 —
 * 집계 쿼리의 방언 차이(H2 ↔ Postgres)를 새로 만들 이유가 없다
 */
@Service
class FeedbackService(
    private val feedbacks: ClubFeedbackRepository,
    private val likes: ClubFeedbackLikeRepository,
    private val comments: ClubFeedbackCommentRepository,
    private val members: ClubMemberRepository,
) {

    /** 최근 글 순, 200개까지 */
    @Transactional(readOnly = true)
    fun list(uid: String): List<FeedbackDto> {
        val viewer = members.requireApproved(uid)
        return toDtos(viewer, feedbacks.findTop200ByOrderByIdDesc())
    }

    @Transactional
    fun create(uid: String, input: FeedbackTextInput, now: Instant = Instant.now()): FeedbackDto {
        val viewer = members.requireApproved(uid)
        val row = feedbacks.save(ClubFeedback(uid, feedbackText(input.text), FeedbackStatus.OPEN, now, now))
        return toDtos(viewer, listOf(row)).single()
    }

    /** 쓴 사람만 — 운영진도 남의 글은 못 고친다(지우기·상태만). 없는 글이 먼저다 — 404 가 403 보다 앞선다 */
    @Transactional
    fun edit(uid: String, id: Long, input: FeedbackTextInput, now: Instant = Instant.now()): FeedbackDto {
        val viewer = members.requireApproved(uid)
        val row = feedbacks.findById(id).orElse(null) ?: throw ClubError.noSuchFeedback()
        if (row.userId != uid) throw ClubError.notAuthor()
        row.text = feedbackText(input.text)
        row.updatedAt = now
        return toDtos(viewer, listOf(row)).single()
    }

    /** 공감·댓글을 먼저 지운다 — 외래 키가 막는다(운영 DB 의 CASCADE 는 손으로 지울 때를 위한 두 번째 줄) */
    @Transactional
    fun delete(uid: String, id: Long): DeletedDto {
        val viewer = members.requireApproved(uid)
        val row = feedbacks.findById(id).orElse(null) ?: throw ClubError.noSuchFeedback()
        if (row.userId != uid && !viewer.isActiveAdmin()) throw ClubError.notAuthor()
        likes.deleteByFeedback(id)
        comments.deleteByFeedback(id)
        feedbacks.delete(row)
        return DeletedDto(id, true)
    }

    /** 멱등 — 같은 값이면 그대로. 자기 글에도 된다(계약). 글 행을 잠가 같은 사람의 연타를 차례로 받는다 */
    @Transactional
    fun like(uid: String, id: Long, input: FeedbackLikeInput, now: Instant = Instant.now()): FeedbackDto {
        val viewer = members.requireApproved(uid)
        val liked = input.liked ?: throw ClubError.badFeedback()
        val row = feedbacks.findForUpdateById(id) ?: throw ClubError.noSuchFeedback()
        val existing = likes.findByFeedbackIdAndUserId(id, uid)
        if (liked && existing == null) likes.save(ClubFeedbackLike(id, uid, now))
        if (!liked && existing != null) likes.delete(existing)
        return toDtos(viewer, listOf(row)).single()
    }

    /** 아무 순서로나 바꾼다. OPEN 으로 되돌리면 언제·누가를 지운다(계약) */
    @Transactional
    fun status(uid: String, id: Long, input: FeedbackStatusInput, now: Instant = Instant.now()): FeedbackDto {
        val viewer = members.requireApproved(uid)
        if (!viewer.isActiveAdmin()) throw ClubError.notAdmin()
        val next = FeedbackStatus.entries.firstOrNull { it.name == input.status } ?: throw ClubError.badStatus()
        val row = feedbacks.findById(id).orElse(null) ?: throw ClubError.noSuchFeedback()
        row.status = next
        row.statusAt = if (next == FeedbackStatus.OPEN) null else now
        row.statusBy = if (next == FeedbackStatus.OPEN) null else uid
        return toDtos(viewer, listOf(row)).single()
    }

    /** 오래된 순 */
    @Transactional(readOnly = true)
    fun comments(uid: String, id: Long): List<FeedbackCommentDto> {
        val viewer = members.requireApproved(uid)
        if (!feedbacks.existsById(id)) throw ClubError.noSuchFeedback()
        val rows = comments.findByFeedbackIdOrderById(id)
        val names = nicknames(rows.map { it.userId })
        return rows.map { it.toDto(viewer, names) }
    }

    @Transactional
    fun comment(uid: String, id: Long, input: FeedbackTextInput, now: Instant = Instant.now()): FeedbackCommentDto {
        val viewer = members.requireApproved(uid)
        val text = boundedText(input.text, COMMENT_MAX_LETTERS, Int.MAX_VALUE, ClubError::badComment)
        if (!feedbacks.existsById(id)) throw ClubError.noSuchFeedback()
        val row = comments.save(ClubFeedbackComment(id, uid, text, viewer.isActiveAdmin(), now))
        return row.toDto(viewer, mapOf(uid to viewer.nickname))
    }

    @Transactional
    fun deleteComment(uid: String, commentId: Long): DeletedDto {
        val viewer = members.requireApproved(uid)
        val row = comments.findById(commentId).orElse(null) ?: throw ClubError.noSuchComment()
        if (row.userId != uid && !viewer.isActiveAdmin()) throw ClubError.notAuthor()
        comments.delete(row)
        return DeletedDto(commentId, true)
    }

    private fun toDtos(viewer: ClubMember, rows: List<ClubFeedback>): List<FeedbackDto> {
        val ids = rows.mapNotNull { it.id }
        val likeRows = if (ids.isEmpty()) emptyList() else likes.findByFeedbackIdIn(ids)
        val likeCount = likeRows.groupingBy { it.feedbackId }.eachCount()
        val likedByMe = likeRows.filter { it.userId == viewer.userId }.map { it.feedbackId }.toSet()
        val commentCount = (if (ids.isEmpty()) emptyList() else comments.findByFeedbackIdIn(ids)).groupingBy { it.feedbackId }.eachCount()
        val names = nicknames(rows.flatMap { listOfNotNull(it.userId, it.statusBy) })
        val admin = viewer.isActiveAdmin()
        return rows.map { f ->
            val id = requireNotNull(f.id)
            FeedbackDto(
                id = id,
                text = f.text,
                status = f.status,
                author = person(f.userId, names),
                createdAt = f.createdAt,
                updatedAt = f.updatedAt,
                statusAt = f.statusAt,
                statusBy = f.statusBy?.let { person(it, names) },
                likes = likeCount[id] ?: 0,
                liked = id in likedByMe,
                comments = commentCount[id] ?: 0,
                mine = f.userId == viewer.userId,
                canDelete = f.userId == viewer.userId || admin,
            )
        }
    }

    private fun ClubFeedbackComment.toDto(viewer: ClubMember, names: Map<String, String>) = FeedbackCommentDto(
        id = requireNotNull(id),
        feedbackId = feedbackId,
        text = text,
        author = person(userId, names),
        admin = admin,
        createdAt = createdAt,
        mine = userId == viewer.userId,
        canDelete = userId == viewer.userId || viewer.isActiveAdmin(),
    )

    /** 내보낸 사람도 멤버 행은 남는다(D28) — 계약의 "저장된 닉네임" 이 그것이다. 행이 없으면 빈 글자 */
    private fun nicknames(uids: Collection<String>): Map<String, String> =
        if (uids.isEmpty()) emptyMap() else members.findByUserIdIn(uids.toSet()).associate { it.userId to it.nickname }

    private fun person(uid: String, names: Map<String, String>) = FeedbackPersonDto(uid, names[uid].orEmpty())

    private fun ClubMember.isActiveAdmin() = role == MemberRole.ADMIN && status == MemberStatus.APPROVED

    private fun feedbackText(raw: String?) = boundedText(raw, TEXT_MAX_LETTERS, TEXT_MAX_LINES, ClubError::badFeedback)

    companion object {
        const val TEXT_MAX_LETTERS = 1000
        const val TEXT_MAX_LINES = 20
        const val COMMENT_MAX_LETTERS = 300
    }
}
