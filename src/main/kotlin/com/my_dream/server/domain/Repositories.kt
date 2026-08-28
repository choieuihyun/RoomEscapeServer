package com.my_dream.server.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.LocalDate

interface StoreRepository : JpaRepository<Store, Long> {
    fun findByStoreKey(storeKey: String): Store?
}

interface ThemeRepository : JpaRepository<Theme, Long> {
    fun findByStoreAndExternalId(store: Store, externalId: String): Theme?
    fun findByStore(store: Store): List<Theme>
}

interface TimeSlotRepository : JpaRepository<TimeSlot, Long> {
    fun findByThemeAndDate(theme: Theme, date: LocalDate): List<TimeSlot>

    /** 조회 API 용. 지점 하루치를 테마와 함께 한 번에 가져온다 (테마당 쿼리를 또 날리지 않도록). */
    @Query(
        """
        select s from TimeSlot s
        join fetch s.theme t
        where t.store = :store and s.date = :date
        order by t.id, s.time
        """,
    )
    fun findByStoreAndDate(store: Store, date: LocalDate): List<TimeSlot>

    /** 이 지점에서 고를 수 있는 날짜. 프론트가 날짜 선택지를 그리는 데 쓴다. */
    @Query(
        """
        select distinct s.date from TimeSlot s
        where s.theme.store = :store and s.date >= :from
        order by s.date
        """,
    )
    fun findDatesByStore(store: Store, from: LocalDate): List<LocalDate>
}

interface WatchRepository : JpaRepository<Watch, Long> {

    fun findByUserIdAndTimeSlot(userId: String, timeSlot: TimeSlot): Watch?

    /**
     * 내 감시 목록. 지난 자리는 빼고 준다 — 지나간 회차를 지켜보고 있다고 말하면 거짓말이다.
     *
     * `join fetch` 로 매장까지 한 번에 끌어온다. 없으면 목록을 그리는 동안
     * 감시 하나마다 쿼리가 더 나간다 (N+1).
     */
    @Query(
        """
        select w from Watch w
        join fetch w.timeSlot s
        join fetch s.theme t
        join fetch t.store
        where w.userId = :userId and s.date >= :from
        order by s.date, s.time
        """,
    )
    fun findActiveByUserId(userId: String, from: LocalDate): List<Watch>

    /** 전이가 난 자리들을 감시하던 사람 전부. 전이 한 건마다 쿼리를 날리지 않으려고 묶어서 받는다. */
    @Query(
        """
        select w from Watch w
        join fetch w.timeSlot s
        join fetch s.theme t
        join fetch t.store
        where s.id in :slotIds
        """,
    )
    fun findByTimeSlotIds(slotIds: Collection<Long>): List<Watch>
}

interface NotificationLogRepository : JpaRepository<NotificationLog, Long> {

    /** 이 감시에 **성공적으로** 보낸 가장 최근 알림. 쿨다운 판정에 쓴다. */
    fun findTopByWatchAndOutcomeOrderBySentAtDesc(watch: Watch, outcome: String): NotificationLog?
}

interface DeviceTokenRepository : JpaRepository<DeviceToken, Long> {

    fun findByUserId(userId: String): List<DeviceToken>

    /** 등록은 upsert 다. 이미 있는 토큰이면 주인과 시각만 갱신한다 */
    fun findByToken(token: String): DeviceToken?
}
