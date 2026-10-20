package com.my_dream.server.domain

import jakarta.persistence.LockModeType
import org.springframework.data.domain.Limit
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

interface StoreRepository : JpaRepository<Store, Long> {
    fun findByStoreKey(storeKey: String): Store?
    fun findByStoreKeyIn(storeKeys: Collection<String>): List<Store>
}

interface ThemeRepository : JpaRepository<Theme, Long> {
    fun findByStoreAndExternalId(store: Store, externalId: String): Theme?
    fun findByStore(store: Store): List<Theme>

    /** `GET /api/themes` — 테마 수백 개에 지점까지 한 번에 */
    @Query("select t from Theme t join fetch t.store")
    fun findAllWithStore(): List<Theme>
}

interface TimeSlotRepository : JpaRepository<TimeSlot, Long> {

    /** 협의 도우미 — 그날 모든 지점의 회차를 테마·지점과 함께 한 번에 (40지점을 하나씩 돌지 않게) */
    @Query("select s from TimeSlot s join fetch s.theme t join fetch t.store where s.date = :date")
    fun findAllOnDate(date: LocalDate): List<TimeSlot>
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

    /** 내보내기가 부른다 — 알림 기록을 먼저 지운 뒤에 ([NotificationLogRepository.deleteByWatchUserId]) */
    @Modifying(flushAutomatically = true)
    @Query("delete from Watch w where w.userId = :userId")
    fun deleteByUserIdInBulk(userId: String): Int

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

    /**
     * 내보낸 사람의 감시를 지우기 **전에** 부른다 (D28 정정). 운영 Postgres 는 CASCADE 로 같이 지워지지만
     * 테스트 H2 는 엔티티로 만든 표라 CASCADE 가 없다 — 이걸 빼면 알림 기록이 있는 감시에서 테스트만 FK 에 걸린다
     */
    @Modifying(flushAutomatically = true)
    @Query("delete from NotificationLog n where n.watch.id in (select w.id from Watch w where w.userId = :userId)")
    fun deleteByWatchUserId(userId: String): Int
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
     * 날짜 범위의 지워지지 않은 일정 — **날짜 미정이고 후보 하나가 범위 안인 것**까지 (D32).
     *
     * **순서를 정하지 않는다** — 서비스가 메모리에서 정한다(미정 일정의 기준 날짜가 후보 표에 있다).
     * ~~여기서 `order by … nulls first`~~ 로 정하던 것을 2026-10-19 3단계에서 옮겼다. 순서를 두 군데 두면 하나는 거짓말이 된다
     */
    @Query(
        """
        select e from ClubEvent e
        where e.deletedAt is null and (
            e.date between :from and :to
            or (e.date is null and exists (
                select 1 from ClubEventCandidate c where c.event = e and c.date between :from and :to
            ))
        )
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

    /** 해 본 테마 자동 기록 대상 (D33) — 예약 완료 · 안 지움 · 날짜가 지남 · 아직 안 함. 날짜 미정은 `date < :today` 에서 빠진다 */
    @Query(
        """
        select e from ClubEvent e
        where e.booked = true and e.deletedAt is null and e.date < :today and e.playedRecordedAt is null
        order by e.date, e.id
        """,
    )
    fun findUnrecordedPast(today: LocalDate): List<ClubEvent>

    /** **벌크로** 단다 — 엔티티로 고치면 `@Version` 이 오른다 (D33). `updatedAt` 도 안 건드린다 */
    @Modifying(flushAutomatically = true)
    @Query("update ClubEvent e set e.playedRecordedAt = :at where e.id in :ids")
    fun markPlayedRecorded(ids: Collection<Long>, at: Instant): Int

    /**
     * 내가 참여한 지난 일정 (§4.9 `GET /api/me/events`) — **(date, id) 내림차순 커서.** `(date, id) < (before, beforeId)`.
     * 날짜만으로 자르면 쪽 경계에 걸친 같은 날 일정이 빠진다. `beforeId = 0` 이면 그날은 통째로 빠진다(= `date < before`).
     * 날짜 미정(null)은 비교에서 저절로 빠진다
     */
    @Query(
        """
        select e from ClubEvent e
        where e.deletedAt is null
          and (e.date < :before or (e.date = :before and e.id < :beforeId))
          and exists (select 1 from ClubEventParticipant p where p.event = e and p.userId = :uid)
        order by e.date desc, e.id desc
        """,
    )
    fun findPastJoined(uid: String, before: LocalDate, beforeId: Long, limit: Limit): List<ClubEvent>

    /** 내가 참여한 지난 일정 **전체 수** — 커서와 상관없이 (프로필의 숫자) */
    @Query(
        """
        select count(e) from ClubEvent e
        where e.deletedAt is null and e.date < :today
          and exists (select 1 from ClubEventParticipant p where p.event = e and p.userId = :uid)
        """,
    )
    fun countPastJoined(uid: String, today: LocalDate): Long

    /** 전날 알림 대상 (D34) */
    fun findByDateAndDeletedAtIsNull(date: LocalDate): List<ClubEvent>
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

interface ClubEventCandidateRepository : JpaRepository<ClubEventCandidate, Long> {

    @Query("select c from ClubEventCandidate c where c.event.id in :eventIds order by c.event.id, c.date")
    fun findByEventIds(eventIds: Collection<Long>): List<ClubEventCandidate>

    fun findByEvent(event: ClubEvent): List<ClubEventCandidate>

    fun findByEventAndDate(event: ClubEvent, date: LocalDate): ClubEventCandidate?

    fun countByEvent(event: ClubEvent): Long

    /** 응답을 먼저 지운 뒤에 부른다 — 응답이 이 행을 가리킨다 (D32 "지우기는 서비스가 직접") */
    @Modifying(flushAutomatically = true)
    @Query("delete from ClubEventCandidate c where c in :candidates")
    fun deleteAllIn(candidates: Collection<ClubEventCandidate>): Int
}

interface ClubEventAvailabilityRepository : JpaRepository<ClubEventAvailability, Long> {

    @Query(
        """
        select a from ClubEventAvailability a join fetch a.candidate c
        where c.event.id in :eventIds order by c.event.id, c.date, a.id
        """,
    )
    fun findByEventIds(eventIds: Collection<Long>): List<ClubEventAvailability>

    fun findByCandidateAndUserId(candidate: ClubEventCandidate, userId: String): ClubEventAvailability?

    /** 후보를 빼기 전에. `clearAutomatically` 는 끈다 — 잠가 둔 일정 엔티티가 떨어져 나간다 (구간 지우기와 같은 이유) */
    @Modifying(flushAutomatically = true)
    @Query("delete from ClubEventAvailability a where a.candidate in :candidates")
    fun deleteByCandidates(candidates: Collection<ClubEventCandidate>): Int

    /** 참여를 취소하거나 빠질 때 — 안 갈 사람의 "됨" 이 남으면 "다 같이 되는 날" 이 틀린다 */
    @Modifying(flushAutomatically = true)
    @Query(
        """
        delete from ClubEventAvailability a
        where a.userId = :userId and a.candidate in (select c from ClubEventCandidate c where c.event = :event)
        """,
    )
    fun deleteByEventAndUserId(event: ClubEvent, userId: String): Int
}

interface MemberProfileRepository : JpaRepository<MemberProfile, String> {
    fun findByUserId(userId: String): MemberProfile?

    /** 멤버 목록에 프로필을 붙일 때 한 번에 — 사람마다 물으면 30명에 쿼리 30번이다 */
    fun findByUserIdIn(userIds: Collection<String>): List<MemberProfile>
}

/** 랭킹 한 칸 — 그 사람이 그날 기록한 테마 수 */
data class PlayedDayCount(val userId: String, val nickname: String, val playedOn: LocalDate, val count: Long)

interface PlayedThemeRepository : JpaRepository<PlayedTheme, Long> {
    fun findByUserId(userId: String): List<PlayedTheme>

    /**
     * 랭킹용 — **지금 승인된** 멤버의 (사람, 날짜) 별 기록 수. 달로 묶는 것은 Kotlin 이 한다:
     * 달을 자르는 SQL 함수가 Postgres 와 H2 에서 달라 테스트와 운영이 다른 SQL 을 돌게 된다 (D35)
     */
    @Query(
        """select new com.my_dream.server.domain.PlayedDayCount(p.userId, m.nickname, p.playedOn, count(p))
           from PlayedTheme p, ClubMember m
           where m.userId = p.userId and m.status = com.my_dream.server.domain.MemberStatus.APPROVED
             and p.playedOn between :from and :to
           group by p.userId, m.nickname, p.playedOn""",
    )
    fun countByDay(from: LocalDate, to: LocalDate): List<PlayedDayCount>
    fun findByUserIdIn(userIds: Collection<String>): List<PlayedTheme>
    fun findByIdAndUserId(id: Long, userId: String): PlayedTheme?
}

interface ClubEventReminderRepository : JpaRepository<ClubEventReminder, Long> {

    /** 이 날짜 기준으로 이미 **보낸**(SENT) 사람들 — (일정 id, uid) */
    @Query("select r from ClubEventReminder r where r.remindFor = :date and r.outcome = 'SENT'")
    fun findSentFor(date: LocalDate): List<ClubEventReminder>
}

interface ClubWishRepository : JpaRepository<ClubWish, Long> {

    fun findByUserId(userId: String): List<ClubWish>

    fun findByUserIdIn(userIds: Collection<String>): List<ClubWish>

    fun findByIdAndUserId(id: Long, userId: String): ClubWish?

    fun countByUserId(userId: String): Long
}

interface SettlementRepository : JpaRepository<Settlement, Long>

/** 일정 목록의 `settlementTotal` 한 줄 */
data class EventTotal(val eventId: Long, val total: Long)

interface SettlementItemRepository : JpaRepository<SettlementItem, Long> {

    fun findByEventIdOrderByPosition(eventId: Long): List<SettlementItem>

    /**
     * 일정 여러 개의 정산 합계를 한 번에 — 일정마다 물으면 목록 하나에 쿼리가 일정 수만큼 는다.
     * 항목이 없는 일정은 줄이 안 나온다(= 정산 없음, `settlementTotal: null`)
     */
    @Query(
        """
        select new com.my_dream.server.domain.EventTotal(i.eventId, sum(i.amount))
        from SettlementItem i where i.eventId in :eventIds group by i.eventId
        """,
    )
    fun totalsByEvent(eventIds: Collection<Long>): List<EventTotal>
}

interface SettlementShareRepository : JpaRepository<SettlementShare, Long> {

    @Query("select s from SettlementShare s join fetch s.item i where i.eventId = :eventId")
    fun findByEventId(eventId: Long): List<SettlementShare>
}

interface SettlementTransferRepository : JpaRepository<SettlementTransfer, Long> {

    fun findByEventId(eventId: Long): List<SettlementTransfer>
}

interface PayInfoRepository : JpaRepository<PayInfo, String>
