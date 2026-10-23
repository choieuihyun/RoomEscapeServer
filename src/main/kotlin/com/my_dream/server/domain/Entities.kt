package com.my_dream.server.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.Version
import jakarta.persistence.UniqueConstraint
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * 매장의 한 지점. [storeKey] 가 자연키다 — 예: `play33-konkuk`.
 * 조회 API 의 `branch` 파라미터와 같은 값이라 외부에 그대로 노출된다.
 */
@Entity
@Table(name = "store", uniqueConstraints = [UniqueConstraint(columnNames = ["store_key"])])
class Store(
    @Column(name = "store_key", nullable = false) val storeKey: String,
    @Column(nullable = false) var brand: String,
    @Column(name = "branch_name", nullable = false) var branchName: String,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}

/**
 * 테마. 자연키는 `(store, externalId)` 다.
 *
 * 이름이 아니라 사이트가 준 ID 를 키로 쓰는 이유: 테마명은 바뀔 수 있고,
 * 이름을 키로 잡으면 이름이 바뀐 순간 **같은 테마가 두 행으로 갈라진다.**
 * 사이트가 ID 를 안 주면 이름으로 대체한다 (그 경우에만 위 위험을 감수한다).
 */
@Entity
@Table(name = "theme", uniqueConstraints = [UniqueConstraint(columnNames = ["store_id", "external_id"])])
class Theme(
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = false)
    val store: Store,
    @Column(name = "external_id", nullable = false) val externalId: String,
    @Column(nullable = false) var name: String,
    var genre: String? = null,
    var capacity: String? = null,
    var runningMinutes: Int? = null,
    var horrorLevel: Double? = null,
    var difficulty: Double? = null,
    @Column(length = 500) var posterUrl: String? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}

/**
 * 회차 하나. 자연키는 `(theme, date, time)` 이고 유니크 제약이 걸려 있다.
 *
 * [available] 의 변화가 이 서비스의 전부다. 그래서 이 행은 **덮어쓰기(upsert)만 한다** —
 * 지우고 다시 넣으면 그 사이에 들어온 조회가 빈 시간표를 보고, 이전 상태도 함께 사라진다.
 */
@Entity
@Table(
    name = "time_slot",
    uniqueConstraints = [UniqueConstraint(columnNames = ["theme_id", "slot_date", "slot_time"])],
)
class TimeSlot(
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "theme_id", nullable = false)
    val theme: Theme,
    @Column(name = "slot_date", nullable = false) val date: LocalDate,
    @Column(name = "slot_time", nullable = false) val time: LocalTime,
    @Column(nullable = false) var available: Boolean,
    @Column(nullable = false) var lastCheckedAt: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}

/**
 * 감시 신청 — "이 자리가 풀리면 알려줘".
 *
 * 자연키는 `(userId, timeSlot)` 이다. 같은 사람이 같은 자리를 두 번 신청할 수 없고,
 * 신청 버튼을 두 번 눌러도 알림이 두 번 가지 않는다. [userId] 는 Firebase uid (`sub`) 다.
 *
 * 지난 날짜는 지우지 않는다. 지우는 배치를 따로 만드는 것보다,
 * 조회할 때 `date >= 오늘` 로 거르는 쪽이 사고가 적다.
 */
@Entity
@Table(name = "watch", uniqueConstraints = [UniqueConstraint(columnNames = ["user_id", "time_slot_id"])])
class Watch(
    @Column(name = "user_id", nullable = false) val userId: String,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "time_slot_id", nullable = false)
    val timeSlot: TimeSlot,
    @Column(name = "created_at", nullable = false) val createdAt: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}

/**
 * 보낸 알림 기록. **쿨다운 판정의 유일한 근거다** (아키텍처 D11).
 *
 * 메모리에 두지 않는 이유: 서버를 재시작하면 기억이 날아가고,
 * 그 직후 수집 한 바퀴에서 방금 보낸 알림이 전부 다시 나간다.
 *
 * [outcome] 이 [SENT] 인 것만 쿨다운으로 센다. 발송이 실패한 건 "알렸다" 가 아니라서
 * 다음 바퀴에 다시 시도해야 한다 — 실패를 쿨다운으로 세면 그 자리는 조용히 묻힌다.
 */
@Entity
@Table(name = "notification_log")
class NotificationLog(
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "watch_id", nullable = false)
    val watch: Watch,
    @Column(name = "sent_at", nullable = false) val sentAt: Instant,
    @Column(nullable = false) val outcome: String,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    companion object {
        const val SENT = "SENT"
        const val FAILED = "FAILED"
    }
}

/**
 * 알림을 받을 기기 하나. **감시가 "무엇을" 이면 이건 "어디로" 다.**
 *
 * [token] 이 자연키다 — user_id 가 아니라. 토큰은 "이 브라우저 설치" 를 가리키므로
 * 같은 기기에서 다른 계정으로 로그인하면 **주인이 바뀌어야 한다.**
 * `(user_id, token)` 으로 묶으면 이전 사용자 행이 남아서 남의 폰으로 알림이 간다.
 *
 * FCM 이 `UNREGISTERED` 로 답하면 그 자리에서 지운다. 안 지우면 죽은 토큰이 쌓여
 * 발송 때마다 시체에 요청을 한 번씩 더 보낸다.
 */
@Entity
@Table(name = "device_token", uniqueConstraints = [UniqueConstraint(columnNames = ["token"])])
class DeviceToken(
    @Column(name = "user_id", nullable = false) var userId: String,
    @Column(nullable = false) val token: String,
    @Column val platform: String?,
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}

/** 동호회 안의 역할. 관리자는 가입 승인·멤버 관리를 한다 (아키텍처 D28) */
enum class MemberRole { ADMIN, MEMBER }

/**
 * 가입 상태. **행이 없는 것**(가입 신청 전)은 여기 없다 — API 가 `NONE` 으로 보여 준다.
 * DB 에 `NONE` 행을 만들지 않는 이유: 로그인만 해 본 사람마다 행이 생기면 "승인 대기" 와 섞인다.
 */
enum class MemberStatus { PENDING, APPROVED, REJECTED, REMOVED }

/**
 * 동호회 멤버 한 사람. [userId] 는 Firebase uid 이고 자연키다.
 *
 * **자격을 토큰(커스텀 클레임)이 아니라 여기 두는 이유** — 토큰은 갱신될 때까지 최대 1시간 옛 값이 돈다.
 * 여기 두면 내보내기가 다음 요청부터 먹는다 (아키텍처 D28).
 *
 * `@Enumerated(STRING)` 이어야 한다. 기본값(ORDINAL)은 숫자로 저장해서 enum 순서를 바꾸는 순간
 * 모든 행의 뜻이 조용히 바뀐다. V6 의 CHECK 도 문자열 기준이다.
 */
@Entity
@Table(name = "club_member", uniqueConstraints = [UniqueConstraint(columnNames = ["user_id"])])
class ClubMember(
    @Column(name = "user_id", nullable = false) val userId: String,
    @Column(nullable = false, length = 20) var nickname: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) var role: MemberRole,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) var status: MemberStatus,
    @Column(name = "created_at", nullable = false) val createdAt: Instant,
    @Column(name = "decided_at") var decidedAt: Instant? = null,
    @Column(name = "decided_by") var decidedBy: String? = null,
    /** 승인된 때. [decidedAt] 은 역할 바꾸기·내보내기에도 새로 찍혀서 따로 둔다 (D35) */
    @Column(name = "approved_at") var approvedAt: Instant? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}

/**
 * 멤버 프로필 한 사람 한 행 (D35). **행이 없으면 아직 안 채운 것** — 화면에는 `profile: null` 로 간다.
 * 칸의 범위 검사는 [com.my_dream.server.club.ProfileRules] 가 한다. [genres] 는 고른 순서대로 쉼표로 이었다
 */
@Entity
@Table(name = "member_profile")
class MemberProfile(
    @Id @Column(name = "user_id") val userId: String,
    @Column(name = "real_name", nullable = false, length = 20) var realName: String,
    @Column(name = "birth_date", nullable = false) var birthDate: LocalDate,
    @Column(nullable = false, length = 7) var mbti: String,
    @Column(name = "room_count", nullable = false) var roomCount: Int,
    @Column(nullable = false, length = 60) var genres: String,
    @Column(name = "life_theme_name", nullable = false, length = 40) var lifeThemeName: String,
    @Column(name = "life_theme_branch", length = 40) var lifeThemeBranch: String?,
    @Column(name = "life_theme_key", length = 80) var lifeThemeKey: String?,
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant,
    /** 거주지역 (V15). 기존 행·옛 앱은 null — 맨 뒤에 기본값으로 둬서 기존 생성자 호출이 그대로 돈다 */
    @Column(length = 20) var residence: String? = null,
)

/**
 * "이 카카오 계정으로 들어오면 이 uid 다".
 *
 * 카카오로 처음 들어온 사람은 `kakao:<회원번호>` uid 를 받고, 기존 아이디 계정에 "연결" 하면
 * 그 계정의 uid 가 들어간다. **양쪽 다 유일하다** — 카카오 하나에 계정 하나, 계정 하나에 카카오 하나.
 */
@Entity
@Table(
    name = "kakao_link",
    uniqueConstraints = [UniqueConstraint(columnNames = ["kakao_id"]), UniqueConstraint(columnNames = ["user_id"])],
)
class KakaoLink(
    @Column(name = "kakao_id", nullable = false) val kakaoId: Long,
    @Column(name = "user_id", nullable = false) val userId: String,
    @Column(name = "created_at", nullable = false) val createdAt: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}

/**
 * 동호회 일정 (아키텍처 D30 · D31). 계약은 Floduler `작업명세서.md` §4.7.
 *
 * **구간·참여자 컬렉션을 여기 매핑하지 않는다.** 매핑하면 참여만 해도 이 행의 [version] 이 오를 수 있고,
 * 그러면 편집 중인 사람이 남의 참여 때문에 충돌을 받는다 (D31). 둘 다 자기 쪽에서 `@ManyToOne` 으로만 가리킨다.
 *
 * 시각은 자정부터의 분이다 — [startMin]·[endMin] 둘 다 null 이면 "하루 종일".
 */
@Entity
@Table(name = "club_event")
class ClubEvent(
    @Column(nullable = false, length = 60) var title: String,
    /** null 이면 날짜 미정 — 후보는 [ClubEventCandidate] (D32) */
    @Column(name = "event_date") var date: LocalDate?,
    @Column(name = "start_min") var startMin: Int?,
    @Column(name = "end_min") var endMin: Int?,
    @Column(length = 60) var location: String?,
    @Column var capacity: Int?,
    @Column(nullable = false) var booked: Boolean,
    @Column(length = 500) var memo: String?,
    @Column(name = "created_by", nullable = false) val createdBy: String,
    @Column(name = "created_at", nullable = false) val createdAt: Instant,
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    /** 낙관적 잠금. **flush 때 오른다** — 응답을 만들기 전에 flush 해야 새 번호가 나간다 (D31) */
    @Version
    @Column(nullable = false)
    var version: Long = 0

    @Column(name = "deleted_at") var deletedAt: Instant? = null
    @Column(name = "deleted_by") var deletedBy: String? = null

    /**
     * 해 본 테마 자동 기록을 한 때 (D33). **읽기 전용** — 벌크 UPDATE 로만 단다.
     * 엔티티로 고치면 version 이 올라 편집 중인 사람이 충돌을 받고, 일정 수정이 이 값을 null 로 덮어쓸 수도 있다
     */
    @Column(name = "played_recorded_at", insertable = false, updatable = false)
    var playedRecordedAt: Instant? = null
}

/** 테마 구간 하나. [position] 순으로 읽는다 */
@Entity
@Table(name = "club_event_segment", uniqueConstraints = [UniqueConstraint(columnNames = ["event_id", "position"])])
class ClubEventSegment(
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id", nullable = false)
    val event: ClubEvent,
    @Column(nullable = false) val position: Int,
    @Column(name = "theme_name", nullable = false, length = 40) val themeName: String,
    @Column(name = "branch_name", length = 40) val branchName: String?,
    @Column(name = "theme_key", length = 80) val themeKey: String?,
    @Column(name = "start_min", nullable = false) val startMin: Int,
    @Column(name = "end_min") val endMin: Int?,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}

/** 일정에 참여한 사람 하나. [addedBy] 는 대신 넣었을 때 넣은 사람 */
@Entity
@Table(name = "club_event_participant", uniqueConstraints = [UniqueConstraint(columnNames = ["event_id", "user_id"])])
class ClubEventParticipant(
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id", nullable = false)
    val event: ClubEvent,
    @Column(name = "user_id", nullable = false) val userId: String,
    @Column(name = "added_by") val addedBy: String?,
    @Column(name = "joined_at", nullable = false) val joinedAt: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}

/** 날짜 미정 일정의 후보 날짜 하나 (D32). 일정이 날짜를 정한 뒤에도 남는다 — 다시 미정으로 돌릴 수 있게 */
@Entity
@Table(name = "club_event_candidate", uniqueConstraints = [UniqueConstraint(columnNames = ["event_id", "cand_date"])])
class ClubEventCandidate(
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id", nullable = false)
    val event: ClubEvent,
    @Column(name = "cand_date", nullable = false) val date: LocalDate,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}

/** 후보 날짜에 대한 참여자 한 명의 응답. 시각이 둘 다 null 이면 종일 됨(또는 안 됨) */
@Entity
@Table(name = "club_event_availability", uniqueConstraints = [UniqueConstraint(columnNames = ["candidate_id", "user_id"])])
class ClubEventAvailability(
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "candidate_id", nullable = false)
    val candidate: ClubEventCandidate,
    @Column(name = "user_id", nullable = false) val userId: String,
    @Column(nullable = false) var available: Boolean,
    @Column(name = "start_min") var startMin: Int?,
    @Column(name = "end_min") var endMin: Int?,
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}

enum class PlayedSource { MANUAL, PLAN, EVENT }

/**
 * 해 본 테마 하나 (D33). "같은 테마인가" 는 [com.my_dream.server.club.PlayedMatch] 한 곳에서만 판정한다 —
 * 규칙이 이행적이지 않아 UNIQUE 로 못 건다
 */
@Entity
@Table(name = "played_theme")
class PlayedTheme(
    @Column(name = "user_id", nullable = false) val userId: String,
    @Column(name = "theme_key", length = 80) val themeKey: String?,
    @Column(name = "theme_name", nullable = false, length = 40) val themeName: String,
    @Column(name = "branch_name", length = 40) val branchName: String?,
    @Column(name = "name_key", nullable = false, length = 200) val nameKey: String,
    @Column(name = "played_on") val playedOn: LocalDate?,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) val source: PlayedSource,
    @Column(name = "event_id") val eventId: Long?,
    @Column(name = "created_at", nullable = false) val createdAt: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}

/** 전날 알림 기록 (D34). [outcome] 은 SENT · FAILED — SENT 만 "보냈다" 로 센다 */
@Entity
@Table(name = "club_event_reminder")
class ClubEventReminder(
    @Column(name = "event_id", nullable = false) val eventId: Long,
    @Column(name = "user_id", nullable = false) val userId: String,
    @Column(name = "remind_for", nullable = false) val remindFor: LocalDate,
    @Column(nullable = false, length = 16) val outcome: String,
    @Column(name = "sent_at", nullable = false) val sentAt: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    companion object {
        const val SENT = "SENT"
        const val FAILED = "FAILED"
    }
}

/**
 * 하고 싶은 테마 한 줄 (D36). "같은 테마인가" 는 해 본 테마와 같이 [com.my_dream.server.club.PlayedMatch] 가 본다 —
 * 그래서 UNIQUE 가 없다
 */
@Entity
@Table(name = "club_wish")
class ClubWish(
    @Column(name = "user_id", nullable = false) val userId: String,
    @Column(name = "theme_key", length = 80) val themeKey: String?,
    @Column(name = "theme_name", nullable = false, length = 40) val themeName: String,
    @Column(name = "branch_name", length = 40) val branchName: String?,
    @Column(name = "name_key", nullable = false, length = 200) val nameKey: String,
    @Column(name = "created_at", nullable = false) val createdAt: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}

/**
 * 홈 공지 (D46). **늘 많아야 한 행**이다 — 쓸 때마다 지우고 새로 넣어 [id] 가 바뀐다(앱이 닫은 공지를 id 로 기억한다).
 * [updatedBy] 는 uid 다. 닉네임은 읽을 때 멤버 행에서 가져온다 — 내보낸 운영진도 멤버 행은 남는다
 */
@Entity
@Table(name = "club_notice")
class ClubNotice(
    @Column(nullable = false, length = 500) val text: String,
    @Column(name = "updated_at", nullable = false) val updatedAt: Instant,
    @Column(name = "updated_by", nullable = false) val updatedBy: String,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}

enum class FeedbackStatus { OPEN, REVIEW, DONE, HOLD }

/**
 * 의견 게시판의 글 (D47). [updatedAt] 은 **글을 고친 때만** 바뀐다 — 상태·공감·댓글로 바꾸면 "고쳐짐" 이 거짓말이 된다.
 * [statusAt]·[statusBy] 는 OPEN 이면 둘 다 null
 */
@Entity
@Table(name = "club_feedback")
class ClubFeedback(
    @Column(name = "user_id", nullable = false) val userId: String,
    @Column(nullable = false, length = 1000) var text: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 8) var status: FeedbackStatus,
    @Column(name = "created_at", nullable = false) val createdAt: Instant,
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant,
    @Column(name = "status_at") var statusAt: Instant? = null,
    @Column(name = "status_by") var statusBy: String? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}

/** 공감 한 개. (글, 사람) 하나 — UNIQUE (D47) */
@Entity
@Table(name = "club_feedback_like", uniqueConstraints = [UniqueConstraint(columnNames = ["feedback_id", "user_id"])])
class ClubFeedbackLike(
    @Column(name = "feedback_id", nullable = false) val feedbackId: Long,
    @Column(name = "user_id", nullable = false) val userId: String,
    @Column(name = "created_at", nullable = false) val createdAt: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}

/** 댓글 (D47). [admin] 은 쓸 때 운영진이었나 — 저장 값이라 나중에 역할이 바뀌어도 그대로다(계약) */
@Entity
@Table(name = "club_feedback_comment")
class ClubFeedbackComment(
    @Column(name = "feedback_id", nullable = false) val feedbackId: Long,
    @Column(name = "user_id", nullable = false) val userId: String,
    @Column(nullable = false, length = 300) val text: String,
    @Column(nullable = false) val admin: Boolean,
    @Column(name = "created_at", nullable = false) val createdAt: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}

/**
 * 정산 머리 (D37). **[version] 은 손으로 올린다** — `@Version` 이 아니다. 계약은 "없으면 0, 첫 저장이 1" 인데
 * `@Version` 은 새 행이 0 에서 시작하고 flush 때 올라 응답에 옛 번호가 나간다(D31 의 함정).
 * 항목을 다 비워도 이 행은 남는다 — 지우면 version 이 0 으로 돌아가 낡은 화면이 덮어쓸 수 있다
 */
@Entity
@Table(name = "settlement")
class Settlement(
    @Id @Column(name = "event_id") val eventId: Long,
    @Column(nullable = false) var version: Long,
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant,
    @Column(name = "updated_by", nullable = false) var updatedBy: String,
)

/** 정산 항목 하나. 일정과 [eventId] 로만 잇는다 — 일정 엔티티를 건드리면 일정 version 이 오를 수 있다 (D31) */
@Entity
@Table(name = "settlement_item", uniqueConstraints = [UniqueConstraint(columnNames = ["event_id", "position"])])
class SettlementItem(
    @Column(name = "event_id", nullable = false) val eventId: Long,
    @Column(nullable = false) val position: Int,
    @Column(nullable = false, length = 20) val label: String,
    @Column(nullable = false) val amount: Int,
    @Column(name = "payer_uid", nullable = false) val payerUid: String,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}

/** 같이 낸 사람의 몫 */
@Entity
@Table(name = "settlement_share", uniqueConstraints = [UniqueConstraint(columnNames = ["item_id", "user_id"])])
class SettlementShare(
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "item_id", nullable = false)
    val item: SettlementItem,
    @Column(name = "user_id", nullable = false) val userId: String,
    @Column(nullable = false) val amount: Int,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}

/** "보냈어요" — 행이 있으면 보낸 것. [amount] 는 체크한 때의 금액이다 — 저장으로 바뀌면 그 체크를 푼다 (D37) */
@Entity
@Table(name = "settlement_transfer", uniqueConstraints = [UniqueConstraint(columnNames = ["event_id", "from_uid", "to_uid"])])
class SettlementTransfer(
    @Column(name = "event_id", nullable = false) val eventId: Long,
    @Column(name = "from_uid", nullable = false) val fromUid: String,
    @Column(name = "to_uid", nullable = false) val toUid: String,
    @Column(nullable = false) var amount: Long,
    @Column(name = "paid_at", nullable = false) var paidAt: Instant,
    @Column(name = "paid_by", nullable = false) var paidBy: String,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}

/** 사람마다 송금 정보 하나 (D37). 🔒 계좌 글 — 로그에 남기지 않는다 */
@Entity
@Table(name = "pay_info")
class PayInfo(
    @Id @Column(name = "user_id") val userId: String,
    @Column(name = "bank_text", length = 60) var bankText: String?,
    @Column(name = "kakaopay_url", length = 200) var kakaopayUrl: String?,
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant,
)
