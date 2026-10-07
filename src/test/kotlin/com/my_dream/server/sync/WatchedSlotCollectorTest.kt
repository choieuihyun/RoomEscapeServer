package com.my_dream.server.sync

import com.my_dream.server.crawler.DaySchedule
import com.my_dream.server.crawler.FetchUnit
import com.my_dream.server.crawler.HostRateLimiter
import com.my_dream.server.crawler.Slot
import com.my_dream.server.crawler.StoreAdapter
import com.my_dream.server.crawler.StoreRef
import com.my_dream.server.crawler.ThemeSchedule
import com.my_dream.server.domain.Store
import com.my_dream.server.domain.StoreRepository
import com.my_dream.server.domain.Theme
import com.my_dream.server.domain.ThemeRepository
import com.my_dream.server.domain.TimeSlot
import com.my_dream.server.domain.TimeSlotRepository
import com.my_dream.server.domain.Watch
import com.my_dream.server.domain.WatchRepository
import com.my_dream.server.notify.LoggingNotifier
import com.my_dream.server.notify.NotificationService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 감시 빠른 확인 (아키텍처 D27).
 *
 * **재는 것은 "요청이 몇 건, 어디로 나갔나" 다.** 이 길은 남의 서버를 더 두드리는 길이라
 * 빨리 알리는 것보다 **안 보내야 할 때 안 보내는 것**이 먼저다 — 그래서 대부분이 "0건" 을 확인한다.
 */
@DataJpaTest
@Import(ScheduleSyncService::class, NotificationService::class, LoggingNotifier::class, ScheduleIngest::class)
class WatchedSlotCollectorTest @Autowired constructor(
    private val ingest: ScheduleIngest,
    private val stores: StoreRepository,
    private val themes: ThemeRepository,
    private val slots: TimeSlotRepository,
    private val watches: WatchRepository,
) {

    private val limiter = HostRateLimiter(0)

    /** 나간 요청. `지점|날짜|테마` */
    private val hits = mutableListOf<String>()

    private val day: LocalDate = LocalDate.now().plusDays(3)
    private val noon: LocalDateTime = day.atTime(12, 0)
    private val evening: LocalTime = LocalTime.of(19, 0)

    /**
     * [themeIds] 가 없으면 지점을 물으면 테마가 전부 오는 매장(플레이33 꼴),
     * 있으면 테마마다 따로 묻는 매장(키이스케이프 꼴)이다.
     */
    private inner class FakeStore(
        override val host: String,
        private val branchKeys: List<String>,
        private val themeIds: List<String>? = null,
        private val respond: (StoreRef, LocalDate, String?) -> DaySchedule = { ref, d, _ -> DaySchedule(ref, d, 7, 7, emptyList()) },
    ) : StoreAdapter {
        override val brand = "가짜"
        override val branches = branchKeys.map { StoreRef(it, brand, it) }
        override fun plan(dates: List<LocalDate>) = branches.flatMap { ref ->
            dates.flatMap { d ->
                (themeIds ?: listOf(null)).map { t ->
                    FetchUnit("${ref.key} ${t ?: "전체"} $d", ref.key, d, t) {
                        limiter.throttled(host) {
                            hits += "${ref.key}|$d|${t ?: "-"}"
                            respond(ref, d, t)
                        }
                    }
                }
            }
        }
    }

    private fun collector(vararg adapters: StoreAdapter, maxUnits: Int = 5, slowMs: Long = 5000) =
        WatchedSlotCollector(
            adapters.toList(), ingest, watches, limiter,
            maxUnits = maxUnits, quietFromHour = 1, quietUntilHour = 7, pauseMinutes = 30, slowMs = slowMs,
        )

    private fun slot(
        storeKey: String,
        themeId: String = "t1",
        date: LocalDate = day,
        time: LocalTime = evening,
        available: Boolean = false,
        checkedAt: Instant = Instant.now(),
    ): TimeSlot {
        val store = stores.findByStoreKey(storeKey) ?: stores.save(Store(storeKey, "가짜", storeKey))
        val theme = themes.findByStoreAndExternalId(store, themeId) ?: themes.save(Theme(store, themeId, "테마 $themeId"))
        return slots.save(TimeSlot(theme, date, time, available, checkedAt))
    }

    private fun watch(slot: TimeSlot, user: String = "나") = watches.save(Watch(user, slot, Instant.now()))

    @Test
    fun `감시가 없으면 요청을 한 건도 보내지 않는다`() {
        slot("a-1")

        val summary = collector(FakeStore("a.example", listOf("a-1", "a-2"))).check(noon)

        assertEquals(0, summary.requests)
        assertTrue(hits.isEmpty(), hits.toString())
    }

    @Test
    fun `감시 걸린 지점과 날짜만 묻는다`() {
        watch(slot("a-1"))

        collector(
            FakeStore("a.example", listOf("a-1", "a-2")),
            FakeStore("b.example", listOf("b-1")),
        ).check(noon)

        // a-2 도, 다른 매장도, 다른 날짜도 안 간다
        assertEquals(listOf("a-1|$day|-"), hits)
    }

    @Test
    fun `같은 지점 같은 날짜의 감시는 몇 개든 요청 하나다`() {
        watch(slot("a-1", "t1", time = LocalTime.of(10, 30)))
        watch(slot("a-1", "t1", time = LocalTime.of(13, 30)))
        val shared = slot("a-1", "t2")
        watch(shared)
        watch(shared, user = "다른 사람")

        val summary = collector(FakeStore("a.example", listOf("a-1"))).check(day.atTime(9, 0))

        assertEquals(1, summary.requests)
    }

    @Test
    fun `테마마다 묻는 매장은 감시 걸린 테마만 묻는다`() {
        // 여기서 테마를 안 가리면 자리 하나에 그 지점 테마 수만큼 요청이 나간다
        watch(slot("k-1", "41"))

        collector(FakeStore("k.example", listOf("k-1", "k-2"), themeIds = listOf("41", "45", "43"))).check(noon)

        assertEquals(listOf("k-1|$day|41"), hits)
    }

    @Test
    fun `지난 자리는 묻지 않는다`() {
        // 날짜는 오늘인데 시각이 지났다. 날짜만 보는 코드는 이 테스트를 못 넘는다
        watch(slot("a-1", time = LocalTime.of(10, 0)))

        val summary = collector(FakeStore("a.example", listOf("a-1"))).check(noon)

        assertEquals(0, summary.requests)
    }

    @Test
    fun `이미 풀린 자리는 묻지 않는다`() {
        // 알림은 이미 갔고 쿨다운 동안은 다시 못 알린다. 자주 볼 이유가 없다
        watch(slot("a-1", available = true))

        val summary = collector(FakeStore("a.example", listOf("a-1"))).check(noon)

        assertEquals(0, summary.requests)
    }

    @Test
    fun `쉬는 시간에는 묻지 않는다`() {
        watch(slot("a-1"))
        val c = collector(FakeStore("a.example", listOf("a-1")))

        assertEquals(0, c.check(day.minusDays(1).atTime(1, 0)).requests, "01:00 부터 쉰다")
        assertEquals(0, c.check(day.minusDays(1).atTime(6, 59)).requests)
        assertEquals(1, c.check(day.minusDays(1).atTime(7, 0)).requests, "07:00 은 쉬는 시간이 아니다")
        assertEquals(1, c.check(day.minusDays(1).atTime(0, 59)).requests)
    }

    @Test
    fun `한 번에 보내는 요청 수에 천장이 있고 오래 안 본 것부터 본다`() {
        val base = Instant.now()
        // b-3 이 제일 오래 안 봤고 b-1 이 방금 봤다
        watch(slot("b-1", checkedAt = base))
        watch(slot("b-2", checkedAt = base.minusSeconds(60)))
        watch(slot("b-3", checkedAt = base.minusSeconds(120)))

        val summary = collector(FakeStore("b.example", listOf("b-1", "b-2", "b-3")), maxUnits = 2).check(noon)

        assertEquals(2, summary.requests, "감시가 셋이어도 둘만 나간다")
        assertEquals(listOf("b-3|$day|-", "b-2|$day|-"), hits)
    }

    @Test
    fun `매진이던 자리가 풀리면 전이로 잡는다`() {
        watch(slot("a-1", "t1"))
        val opened = FakeStore("a.example", listOf("a-1")) { ref, d, _ ->
            val schedule = ThemeSchedule("t1", "테마 t1", null, null, null, null, null, null, listOf(Slot(evening, true)))
            DaySchedule(ref, d, 7, 7, listOf(schedule))
        }

        val c = collector(opened)

        assertEquals(1, c.check(noon).transitions)
        // 풀린 것이 저장됐으니 다음 번에는 후보가 아니다
        assertEquals(0, c.check(noon.plusMinutes(2)).requests)
    }

    @Test
    fun `요청이 실패하면 그 호스트를 쉬고 시간이 지나면 다시 본다`() {
        watch(slot("a-1"))
        watch(slot("b-1"))
        val failing = FakeStore("a.example", listOf("a-1")) { _, _, _ ->
            // 크롤러가 감싸서 던지는 경우까지 본다
            throw IllegalStateException("수집 실패", HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS))
        }
        val c = collector(failing, FakeStore("b.example", listOf("b-1")))

        val first = c.check(noon)
        assertEquals(1, first.failures)
        assertEquals(2, first.requests, "다른 호스트는 계속 본다")

        hits.clear()
        c.check(noon.plusMinutes(2))
        assertEquals(listOf("b-1|$day|-"), hits, "쉬는 호스트로는 안 간다")

        hits.clear()
        c.check(noon.plusMinutes(31))
        assertTrue("a-1|$day|-" in hits, "30분이 지나면 다시 물어 본다: $hits")
    }

    @Test
    fun `응답이 느리면 받은 것은 저장하고 그 호스트를 쉰다`() {
        watch(slot("a-1"))
        val slow = FakeStore("a.example", listOf("a-1")) { ref, d, _ ->
            Thread.sleep(60)
            DaySchedule(ref, d, 7, 7, emptyList())
        }
        val c = collector(slow, slowMs = 20)

        val first = c.check(noon)
        assertEquals(1, first.requests)
        assertEquals(0, first.failures, "느린 것은 실패가 아니다")

        assertEquals(0, c.check(noon.plusMinutes(2)).requests)
    }

    @Test
    fun `전체 바퀴와 같이 돌아도 같은 호스트로는 간격을 지킨다`() {
        // 두 길이 다른 스레드에서 같은 서버를 두드린다. 속도 규칙은 스레드가 아니라 요청이 나가는 자리에서 지켜야 한다
        val delayMs = 60L
        val shared = HostRateLimiter(delayMs)
        val sentAt = mutableListOf<Long>()
        fun adapter(branchKey: String) = object : StoreAdapter {
            override val host = "same.example"
            override val brand = "가짜"
            override val branches = listOf(StoreRef(branchKey, brand, branchKey))
            override fun plan(dates: List<LocalDate>) = dates.map { d ->
                FetchUnit("$branchKey $d", branchKey, d) {
                    shared.throttled(host) {
                        synchronized(sentAt) { sentAt += System.nanoTime() / 1_000_000 }
                        DaySchedule(branches.single(), d, 7, 7, emptyList())
                    }
                }
            }
        }
        watch(slot("w-1"))
        val lane = WatchedSlotCollector(listOf(adapter("w-1")), ingest, watches, shared, 5, 1, 7, 30, 5000)
        // 전체 바퀴 쪽은 요청만 흉내낸다. 저장까지 하면 테스트 트랜잭션이 쥔 행을 다른 스레드가 기다린다
        val sweepUnits = adapter("s-1").plan((0..3).map { day.plusDays(it.toLong()) })

        val pool = Executors.newSingleThreadExecutor()
        pool.submit { sweepUnits.forEach { it.fetch() } }
        repeat(3) { lane.check(noon) }
        pool.shutdown()
        pool.awaitTermination(30, TimeUnit.SECONDS)

        val gaps = sentAt.sorted().zipWithNext { a, b -> b - a }
        assertEquals(7, sentAt.size)
        assertTrue(gaps.all { it >= delayMs - 10 }, "간격: $gaps")
    }
}
