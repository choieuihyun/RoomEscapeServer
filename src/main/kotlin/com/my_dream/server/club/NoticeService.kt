package com.my_dream.server.club

import com.my_dream.server.domain.ClubMember
import com.my_dream.server.domain.ClubMemberRepository
import com.my_dream.server.domain.ClubNotice
import com.my_dream.server.domain.ClubNoticeRepository
import com.my_dream.server.domain.MemberRole
import com.my_dream.server.domain.MemberStatus
import com.my_dream.server.notify.Notifier
import com.my_dream.server.notify.Push
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

// --- 계약 (Floduler `작업명세서.md` §4.14). 모양을 바꾸려면 그 문서부터 ---

/** 본문. **전부 null 을 받는다** — 빠진 글을 여기서 걸러야 계약의 `BAD_NOTICE` 가 나간다 */
data class NoticeInput(val text: String? = null, val notify: Boolean? = null)

data class NoticeAuthorDto(val uid: String, val nickname: String)

data class NoticeDto(val id: Long, val text: String, val updatedAt: Instant, val updatedBy: NoticeAuthorDto)

data class NoticeDeletedDto(val deleted: Boolean = true)

/** "공지를 알려라" — 커밋이 끝난 뒤 [ClubAlertListener] 가 받는다. 본문은 저장할 때 만들어 싣는다 */
data class NoticePosted(val author: String, val body: String)

/**
 * 홈 공지 (아키텍처 D46). 보기는 승인 멤버, 쓰기·내리기는 운영진.
 *
 * **쓸 때마다 지우고 새로 넣는다** — 앱이 "닫은 공지" 를 id 로 기억하므로 고친 공지도 새 id 여야 다시 보인다.
 */
@Service
class NoticeService(
    private val notices: ClubNoticeRepository,
    private val members: ClubMemberRepository,
    private val notifier: Notifier,
    private val publisher: ApplicationEventPublisher,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val lastNotified = ConcurrentHashMap<String, Instant>()

    /** 없으면 null */
    @Transactional(readOnly = true)
    fun current(uid: String): NoticeDto? {
        members.requireApproved(uid)
        return notices.findTopByOrderByIdDesc()?.let { it.toDto(members.findByUserId(it.updatedBy)?.nickname.orEmpty()) }
    }

    /**
     * 알림 제한은 **저장과 따로** 본다 — 1분 안에 또 알리려 해도 글은 저장된다(계약: 오류 아님).
     * 막지 않으면 "고치기" 를 몇 번 누를 때마다 멤버 전원의 폰이 운다
     */
    @Transactional
    fun put(uid: String, input: NoticeInput, now: Instant = Instant.now()): NoticeDto {
        val admin = requireAdmin(uid)
        val text = clean(input.text)
        notices.deleteAllInBatch()
        val row = notices.save(ClubNotice(text, now, uid))
        if (input.notify == true && claim(uid, now)) publisher.publishEvent(NoticePosted(uid, preview(text)))
        return row.toDto(admin.nickname)
    }

    /** 없어도 성공 (멱등) */
    @Transactional
    fun delete(uid: String): NoticeDeletedDto {
        requireAdmin(uid)
        notices.deleteAllInBatch()
        return NoticeDeletedDto()
    }

    /**
     * 승인 멤버 전원(쓴 사람 빼고)에게 한 번. 기기가 없는 사람은 FcmNotifier 가 조용히 건너뛴다.
     * 🔒 공지 글은 로그에 안 남긴다 — 멤버끼리 보는 글이다. 사람 수만 남긴다
     * @return 보낸 사람 수
     */
    fun notifyMembers(posted: NoticePosted): Int {
        val to = members.findByStatusOrderByCreatedAt(MemberStatus.APPROVED).map { it.userId }.filter { it != posted.author }
        to.forEach { notifier.push(Push(it, TITLE, posted.body, "#notice")) }
        log.info("공지 알림 — {}명", to.size)
        return to.size
    }

    private fun requireAdmin(uid: String): ClubMember =
        members.findByUserId(uid)?.takeIf { it.role == MemberRole.ADMIN && it.status == MemberStatus.APPROVED } ?: throw ClubError.notAdmin()

    /** 같은 운영진은 1분에 한 번. 비교와 기록을 한 번에 한다 ([PushTestService] 와 같다). 메모리라 재시작하면 풀린다(계약) */
    private fun claim(uid: String, now: Instant): Boolean {
        var ok = false
        lastNotified.compute(uid) { _, prev ->
            if (prev == null || Duration.between(prev, now) >= GAP) {
                ok = true
                now
            } else {
                prev
            }
        }
        return ok
    }

    private fun ClubNotice.toDto(nickname: String) = NoticeDto(requireNotNull(id), text, updatedAt, NoticeAuthorDto(updatedBy, nickname))

    companion object {
        const val MAX_LETTERS = 500
        const val MAX_LINES = 10
        const val PREVIEW_LETTERS = 60
        const val TITLE = "새 공지가 있어요"
        val GAP: Duration = Duration.ofMinutes(1)

        fun clean(raw: String?): String = boundedText(raw, MAX_LETTERS, MAX_LINES, ClubError::badNotice)

        /** 알림 본문 — 첫 줄 앞 60자, 넘으면 `…`. 코드 포인트로 자른다 — UTF-16 으로 자르면 이모지가 반쪽 난다 */
        fun preview(text: String): String {
            val line = text.lines().first().trimEnd()
            if (line.codePointCount(0, line.length) <= PREVIEW_LETTERS) return line
            return line.substring(0, line.offsetByCodePoints(0, PREVIEW_LETTERS)) + "…"
        }
    }
}
