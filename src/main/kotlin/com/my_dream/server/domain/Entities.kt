package com.my_dream.server.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
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
