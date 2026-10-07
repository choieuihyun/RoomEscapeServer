package com.my_dream.server.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.LocalDate
import java.time.LocalTime

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
     *
     * **날짜만이 아니라 시각까지 본다** (2026-09-01 수정).
     * 전에는 `s.date >= :from` 이라 **오늘 오전 자리가 저녁까지 목록에 남았다.**
     * 오후 6시에 오전 10시 자리가 풀려 봐야 예약을 못 하므로 그 감시는 이미 의미가 없고,
     * 그런데도 **한 사람당 3개 한도에서 한 칸을 잡고 있었다.**
     *
     * 목록과 한도가 **같은 쿼리**를 쓰므로 화면에 보이는 개수와 한도에 세는 개수가 항상 같다 —
     * 여기가 갈라지면 "목록은 비었는데 못 건다" 가 된다.
     */
    @Query(
        """
        select w from Watch w
        join fetch w.timeSlot s
        join fetch s.theme t
        join fetch t.store
        where w.userId = :userId
          and (s.date > :date or (s.date = :date and s.time > :time))
        order by s.date, s.time
        """,
    )
    fun findActiveByUserId(userId: String, date: LocalDate, time: LocalTime): List<Watch>

    /**
     * 감시 빠른 확인의 **후보** — 아직 매진인 채로 감시가 걸려 있는 자리 (아키텍처 D27).
     *
     * **`s.date >= :from` 은 거칠게 거르는 용도일 뿐이다.** 오늘 이미 지난 회차가 섞여 오므로
     * 부르는 쪽이 [isPast] 로 한 번 더 거른다. 여기에 시각 조건까지 적으면 "지난 자리" 의 뜻이
     * 또 한 군데 생긴다 (`PastSlot.kt` 가 왜 한 곳에 모았는지 적어 뒀다).
     *
     * **이미 풀린 자리(`available = true`)는 뺀다.** 알림은 이미 갔고 쿨다운(D11)이 1시간이라,
     * 그동안 자주 봐도 다시 알릴 수 없다. 다시 매진이 되면 전체 바퀴가 그걸 저장하고,
     * 그때부터 다시 후보가 된다.
     */
    @Query(
        """
        select w from Watch w
        join fetch w.timeSlot s
        join fetch s.theme t
        join fetch t.store
        where s.available = false and s.date >= :from
        """,
    )
    fun findWaitingFrom(from: LocalDate): List<Watch>

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
