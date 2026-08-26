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
