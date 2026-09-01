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

        val mine = service.list("나").watches
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

        assertTrue(service.list("나").watches.isEmpty())
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
        assertEquals(1, service.list("나").watches.size)
        assertEquals(2, watches.count().toInt())
    }

    @Test
    fun `한도까지만 걸린다`() {
        // 기본 3개. 크롤 부하와 무관하고(D3) 우리 DB 를 지키는 값이다
        repeat(3) { i -> service.register("나", requireNotNull(slot(LocalDate.now().plusDays(2L + i)).id)) }

        val e = assertFailsWith<WatchLimitExceeded> {
            service.register("나", requireNotNull(future.id))
        }
        assertEquals(3, e.limit)
        assertEquals(3, service.list("나").watches.size, "실패했는데 늘어나면 안 된다")
    }

    @Test
    fun `이미 내 것인 자리는 한도가 차 있어도 다시 눌린다`() {
        // ⚠️ 한도 검사를 먼저 하면 여기서 막힌다. 이미 내 감시인데 "한도 초과" 가 뜨면
        // 사용자는 **뭘 지워야 할지 알 수 없다** — 지울 것이 없기 때문이다
        val slots3 = List(3) { i -> slot(LocalDate.now().plusDays(2L + i)) }
        slots3.forEach { service.register("나", requireNotNull(it.id)) }

        val again = service.register("나", requireNotNull(slots3.first().id))

        assertEquals(3, service.list("나").watches.size, "행이 늘면 알림이 두 번 간다")
        assertTrue(again.id > 0)
    }

    @Test
    fun `남이 건 감시는 내 한도를 안 먹는다`() {
        repeat(3) { i -> service.register("너", requireNotNull(slot(LocalDate.now().plusDays(2L + i)).id)) }

        service.register("나", requireNotNull(future.id))

        assertEquals(1, service.list("나").watches.size)
    }

    @Test
    fun `목록이 한도를 같이 알려 준다`() {
        // 화면이 `2 / 3` 을 그리려면 한도를 알아야 한다. 프론트에 숫자를 박으면
        // 서버에서 바꿨을 때 두 값이 조용히 갈라진다
        assertEquals(3, service.list("나").limit)
    }

    @Test
    fun `오늘인데 시각이 지난 자리는 목록에서도 한도에서도 빠진다`() {
        // ⚠️ 전에는 날짜만 봐서 **오늘 오전 자리가 저녁까지 한 칸을 잡고 있었다.**
        // 오후 6시에 오전 10시 자리가 풀려 봐야 예약을 못 하므로 그 감시는 이미 의미가 없다.
        val 남은시각 = LocalTime.now().plusHours(2)
        // 지난 시각 자리는 **등록 경로로는 못 들어간다**(아래 `오늘인데 시각이 지난 자리는 감시를 걸 수 없다`).
        // 이미 걸려 있던 감시가 시간이 지나 이 상태가 되는 것을 흉내내려고 직접 심는다.
        //
        // ⚠️ 2026-09-01 이전에는 여기서 `service.register` 를 불렀고 **그게 통과했다.**
        // 그 통과가 곧 버그였다 — 이 테스트가 구멍에 기대고 있었던 것이다
        watches.save(
            com.my_dream.server.domain.Watch(
                "나",
                slots.save(TimeSlot(theme, LocalDate.now(), LocalTime.MIN, available = false, lastCheckedAt = Instant.now())),
                Instant.now(),
            ),
        )
        service.register("나", requireNotNull(slots.save(
            TimeSlot(theme, LocalDate.now(), 남은시각, available = false, lastCheckedAt = Instant.now()),
        ).id))

        val mine = service.list("나")

        // 목록과 한도가 **같은 쿼리**를 쓴다. 갈라지면 "목록은 비었는데 못 건다" 가 된다
        assertEquals(1, mine.watches.size, "지난 시각 자리는 목록에서 빠져야 한다")
        assertEquals(남은시각.hour * 60 + 남은시각.minute, mine.watches.single().t)

        // 한 칸이 비었으니 두 개를 더 걸 수 있다 (한도 3)
        service.register("나", requireNotNull(slot(LocalDate.now().plusDays(2)).id))
        service.register("나", requireNotNull(slot(LocalDate.now().plusDays(3)).id))
        assertEquals(3, service.list("나").watches.size)
    }

    @Test
    fun `오늘인데 시각이 지난 자리는 감시를 걸 수 없다`() {
        // ⚠️ 이것이 2026-09-01 에 운영에서 실제로 뚫려 있던 구멍이다.
        // 등록은 `date < today` 만 봤고 목록·한도는 시각까지 봤다. 그래서
        // **10:55 에 만든 10:20 자리 감시가 저장은 되고 목록엔 안 보이는** 행이 3건 생겼다.
        // 화면에 id 가 안 나오니 사용자는 지울 수도 없다.
        //
        // `LocalTime.MIN`(00:00) 은 자정 정각에 돌려도 "지난 것"이다 — 경계가 `<=` 라서다
        val 오늘_지난시각 = slot(LocalDate.now(), LocalTime.MIN)

        val e = assertFailsWith<ResponseStatusException> {
            service.register("나", requireNotNull(오늘_지난시각.id))
        }

        assertEquals(HttpStatus.BAD_REQUEST, e.statusCode)
        assertEquals(0, watches.count().toInt(), "거부했으면 행이 남으면 안 된다")
    }

    @Test
    fun `등록 거부와 목록 제외가 같은 자리에서 갈린다`() {
        // **이 테스트가 진짜 방어선이다.** 위 두 테스트는 각자 한쪽만 본다 —
        // 등록이 막히는지, 목록에서 빠지는지. 그런데 이번 버그는 **둘이 서로 다른 답을 한 것**이지
        // 어느 한쪽이 틀린 게 아니었다. 그래서 "둘이 같은 답을 하는가" 를 따로 재야 한다.
        //
        // 시각을 하루에 걸쳐 뿌리고, **기대값을 `isPast` 로 계산한다.**
        // 테스트가 몇 시에 돌든 경계가 그 사이 어딘가에 있게 되고, 양쪽이 어긋나면 깨진다.
        val 시각들 = listOf(
            LocalTime.MIN, LocalTime.of(6, 0), LocalTime.of(12, 0),
            LocalTime.of(18, 0), LocalTime.of(23, 59, 59),
        )
        val 자리들 = 시각들.map { slot(LocalDate.now(), it) }

        // ① 등록이 거부한 자리
        val 등록거부 = 자리들.filter { s ->
            runCatching { service.register("나", requireNotNull(s.id)) }.isFailure
        }.map { it.time }.toSet()

        // ② 목록에 안 나온 자리 — 등록에 성공한 것 중에서
        val 목록에보임 = service.list("나").watches.map { LocalTime.of(it.t / 60, it.t % 60) }.toSet()
        val 목록제외 = 자리들.map { it.time }.filter { it.withSecond(0) !in 목록에보임 }.toSet()

        assertEquals(
            등록거부, 목록제외,
            "등록이 거부한 자리와 목록에서 빠지는 자리가 다르다 — " +
                "이 둘이 갈라지면 사용자가 못 보고 못 지우는 감시가 쌓인다",
        )
        assertTrue(등록거부.isNotEmpty(), "하루에 걸쳐 뿌렸으니 지난 자리가 하나는 있어야 한다")
    }
}
