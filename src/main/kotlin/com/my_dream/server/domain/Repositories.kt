package com.my_dream.server.domain

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
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

    /** 이 사람들 중 기기가 하나라도 있는 사람. 멤버 관리 화면이 사람마다 묻지 않게 한 번에 */
    @Query("select distinct d.userId from DeviceToken d where d.userId in :userIds")
    fun findUserIdsWithDevice(userIds: Collection<String>): List<String>
}

interface ClubMemberRepository : JpaRepository<ClubMember, Long> {

    fun findByUserId(userId: String): ClubMember?

    fun findByStatusOrderByCreatedAt(status: MemberStatus): List<ClubMember>

    /** 일정의 참여자·만든 사람 닉네임을 한 번에. 잠그지 않는다 — 일정 작업은 멤버 행을 잠그면 안 된다 (D31) */
    fun findByUserIdIn(userIds: Collection<String>): List<ClubMember>

    fun findAllByOrderByCreatedAt(): List<ClubMember>

    /**
     * 관리 작업의 대상을 **잠그고** 읽는다 (`SELECT … FOR UPDATE`).
     * 두 관리자가 같은 사람을 동시에 승인·내보내기 하면, 뒤에 온 쪽은 앞의 결과를 보고 판단한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from ClubMember m where m.userId = :userId")
    fun findForUpdateByUserId(userId: String): ClubMember?

    /**
     * 승인된 관리자 **전부**를 잠그고 읽는다. "마지막 관리자" 판정용이다.
     *
     * 대상 한 줄만 잠그면 안 된다 — 관리자 A·B 가 동시에 서로를 내리면 각자 "관리자 둘" 을 보고 통과해서
     * 0명이 된다. 관리자 행을 전부 잠그면 뒤에 온 쪽은 앞의 결과(하나 남음)를 보고 막힌다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from ClubMember m where m.role = :role and m.status = :status order by m.id")
    fun findForUpdateByRoleAndStatus(role: MemberRole, status: MemberStatus): List<ClubMember>
}

interface KakaoLinkRepository : JpaRepository<KakaoLink, Long> {
    fun findByKakaoId(kakaoId: Long): KakaoLink?
    fun findByUserId(userId: String): KakaoLink?
    fun existsByUserId(userId: String): Boolean
}

interface ClubEventRepository : JpaRepository<ClubEvent, Long> {

    /**
     * 날짜 범위의 지워지지 않은 일정. 순서는 날짜 → 시작(**하루 종일 먼저**) → id (§4.7).
     *
     * `nulls first` 를 **직접 적는다.** Postgres 는 오름차순에서 NULL 을 맨 뒤에 두고 H2 는 앞에 둔다 —
     * 안 적으면 테스트(H2)는 통과하고 운영(Postgres)에서 순서가 뒤집힌다
     */
    @Query(
        """
        select e from ClubEvent e
        where e.deletedAt is null and e.date between :from and :to
        order by e.date, e.startMin asc nulls first, e.id
        """,
    )
    fun findLiveBetween(from: LocalDate, to: LocalDate): List<ClubEvent>

    /**
     * 참여·수정·예약 상태를 바꾸기 전에 **일정 행 하나만** 잠근다 (D31).
     * 잠금은 version 을 올리지 않는다(`PESSIMISTIC_WRITE`) — 참여가 편집을 막으면 안 된다
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from ClubEvent e where e.id = :id")
    fun findForUpdate(id: Long): ClubEvent?
}

interface ClubEventSegmentRepository : JpaRepository<ClubEventSegment, Long> {

    @Query("select s from ClubEventSegment s where s.event.id in :eventIds order by s.event.id, s.position")
    fun findByEventIds(eventIds: Collection<Long>): List<ClubEventSegment>

    /**
     * 구간을 통째로 바꾸기 전에 **먼저 내보내는** 지우기 (D30 "순서 함정").
     * `flushAutomatically` 로 앞의 변경을 먼저 내보낸다. `clearAutomatically` 는 **켜지 않는다** —
     * 켜면 잠가 둔 일정 엔티티가 떨어져 나가 뒤의 변경이 조용히 안 저장된다
     */
    @Modifying(flushAutomatically = true)
    @Query("delete from ClubEventSegment s where s.event = :event")
    fun deleteByEvent(event: ClubEvent): Int
}

interface ClubEventParticipantRepository : JpaRepository<ClubEventParticipant, Long> {

    @Query("select p from ClubEventParticipant p where p.event.id in :eventIds order by p.event.id, p.joinedAt, p.id")
    fun findByEventIds(eventIds: Collection<Long>): List<ClubEventParticipant>

    fun countByEvent(event: ClubEvent): Long

    fun findByEventAndUserId(event: ClubEvent, userId: String): ClubEventParticipant?
}
