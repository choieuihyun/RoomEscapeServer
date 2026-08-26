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
    var horrorLevel: Int? = null,
    var difficulty: Int? = null,
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
