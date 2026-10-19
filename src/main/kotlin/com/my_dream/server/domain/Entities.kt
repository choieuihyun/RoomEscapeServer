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
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}

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
    @Column(name = "event_date", nullable = false) var date: LocalDate,
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
