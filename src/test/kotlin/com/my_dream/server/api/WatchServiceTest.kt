package com.my_dream.server.api

import com.my_dream.server.domain.Store
import com.my_dream.server.domain.StoreRepository
import com.my_dream.server.domain.Theme
import com.my_dream.server.domain.ThemeRepository
import com.my_dream.server.domain.TimeSlot
import com.my_dream.server.domain.TimeSlotRepository
import com.my_dream.server.domain.WatchRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** 감시 등록 — 특히 **남의 것을 못 건드리는지**. */
@DataJpaTest
@Import(WatchService::class)
class WatchServiceTest @Autowired constructor(
    private val service: WatchService,
    private val stores: StoreRepository,
    private val themes: ThemeRepository,
    private val slots: TimeSlotRepository,
    private val watches: WatchRepository,
) {

    private lateinit var theme: Theme
    private lateinit var future: TimeSlot

    @BeforeEach
    fun setUp() {
        val store = stores.save(Store("play33-daejeon", "플레이33", "대전점"))
        theme = themes.save(Theme(store, "34", "우울해서 빵 샀어"))
        future = slot(LocalDate.now().plusDays(1))
    }

    private fun slot(date: LocalDate, time: LocalTime = LocalTime.of(19, 35)) =
        slots.save(TimeSlot(theme, date, time, available = false, lastCheckedAt = Instant.now()))

    @Test
    fun `감시를 걸면 목록에 보인다`() {
        service.register("나", requireNotNull(future.id))

        val mine = service.list("나")
        assertEquals(1, mine.size)
        assertEquals("대전점", mine.single().branch)
        assertEquals(19 * 60 + 35, mine.single().t, "자정부터의 분 — 조회 API 와 같은 형식")
    }

    @Test
    fun `두 번 걸어도 하나다`() {
        val first = service.register("나", requireNotNull(future.id))
        val second = service.register("나", requireNotNull(future.id))

        // 새로 만들면 전이 한 번에 알림이 두 번 간다
        assertEquals(first.id, second.id)
        assertEquals(1, watches.count().toInt())
    }

    @Test
    fun `남의 감시는 내 목록에 없다`() {
        service.register("너", requireNotNull(future.id))

        assertTrue(service.list("나").isEmpty())
    }

    @Test
    fun `남의 감시는 지울 수 없다`() {
        val theirs = service.register("너", requireNotNull(future.id))

        val e = assertFailsWith<ResponseStatusException> { service.cancel("나", theirs.id) }

        // "권한 없음" 이 아니라 "없음" 이다 — 있다는 사실 자체를 알려 줄 이유가 없다
        assertEquals(HttpStatus.NOT_FOUND, e.statusCode)
        assertEquals(1, watches.count().toInt())
    }

    @Test
    fun `내 감시는 지울 수 있다`() {
        val mine = service.register("나", requireNotNull(future.id))

        service.cancel("나", mine.id)

        assertEquals(0, watches.count().toInt())
    }

    @Test
    fun `지난 회차는 감시할 수 없다`() {
        val past = slot(LocalDate.now().minusDays(1))

        val e = assertFailsWith<ResponseStatusException> { service.register("나", requireNotNull(past.id)) }

        // 받아 두면 영영 안 울리는 감시가 쌓인다
        assertEquals(HttpStatus.BAD_REQUEST, e.statusCode)
    }

    @Test
    fun `없는 회차는 감시할 수 없다`() {
        val e = assertFailsWith<ResponseStatusException> { service.register("나", 999_999) }

        assertEquals(HttpStatus.NOT_FOUND, e.statusCode)
    }

    @Test
    fun `날짜가 지난 감시는 목록에서 빠진다`() {
        service.register("나", requireNotNull(future.id))
        // 어제 자리를 직접 심는다 (등록 경로로는 못 들어간다)
        watches.save(com.my_dream.server.domain.Watch("나", slot(LocalDate.now().minusDays(2)), Instant.now()))

        // 지나간 회차를 지켜보고 있다고 말하면 거짓말이다
        assertEquals(1, service.list("나").size)
        assertEquals(2, watches.count().toInt())
    }
}
