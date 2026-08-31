package com.my_dream.server.sync

import com.my_dream.server.crawler.DaySchedule
import com.my_dream.server.crawler.FetchUnit
import com.my_dream.server.crawler.HostRateLimiter
import com.my_dream.server.crawler.StoreAdapter
import com.my_dream.server.crawler.StoreRef
import com.my_dream.server.notify.LoggingNotifier
import com.my_dream.server.notify.NotificationService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 한 바퀴가 **사이트 단위로** 병렬이고, 같은 사이트로는 줄을 서는지 (아키텍처 D13).
 */
@DataJpaTest
@Import(ScheduleSyncService::class, NotificationService::class, LoggingNotifier::class, ScheduleIngest::class)
class StoreCollectorTest @Autowired constructor(private val ingest: ScheduleIngest) {

    private val delayMs = 60L
    private val limiter = HostRateLimiter(delayMs)
    private val hits = mutableListOf<Pair<String, Long>>()

    /** 동시에 몇 개가 떠 있었는지. "호스트가 늘면 파도로 쪼개지는" 것을 재려면 이게 필요하다 */
    private val inFlight = AtomicInteger()
    private val peakInFlight = AtomicInteger()

    /** 요청 1회를 흉내낸다. **속도 제한을 통과해서** 나간 시각을 적는다 */
    private inner class FakeStore(
        override val host: String,
        override val brand: String,
        private val branchNames: List<String>,
    ) : StoreAdapter {
        override val branches = branchNames.map { StoreRef("$brand-$it", brand, it) }
        override fun plan(dates: List<LocalDate>) = branchNames.flatMap { b ->
            dates.map { d ->
                FetchUnit("$b $d") {
                    limiter.throttled(host) {
                        val now = inFlight.incrementAndGet()
                        peakInFlight.updateAndGet { peak -> maxOf(peak, now) }
                        try {
                            synchronized(hits) { hits += host to System.nanoTime() / 1_000_000 }
                            // 겹치는 구간을 만들어 줘야 동시 실행 수가 측정된다
                            Thread.sleep(delayMs / 2)
                            DaySchedule(StoreRef("$brand-$b", brand, b), d, 7, 7, emptyList())
                        } finally {
                            inFlight.decrementAndGet()
                        }
                    }
                }
            }
        }
    }

    private fun collector(vararg adapters: StoreAdapter, concurrency: Int = 4) =
        StoreCollector(adapters.toList(), ingest, PollingSchedule(rangeDays = 7, farRangeDays = 7, intervalMs = 300_000), concurrency, dbPoolSize = 24)

    private fun gaps(host: String) =
        hits.filter { it.first == host }.map { it.second }.sorted().zipWithNext { a, b -> b - a }

    private val dates = (0..2).map { LocalDate.now().plusDays(it.toLong()) }

    @Test
    fun `어댑터가 낸 요청을 전부 돈다`() {
        val summary = collector(FakeStore("a.example", "가", listOf("1지점", "2지점"))).collect(dates)

        assertEquals(2 * dates.size, hits.size, "지점 × 날짜")
        assertEquals(0, summary.failures)
    }

    @Test
    fun `같은 호스트로는 간격을 지킨다`() {
        collector(FakeStore("a.example", "가", listOf("1지점", "2지점", "3지점"))).collect(dates)

        val g = gaps("a.example")
        assertTrue(g.all { it >= delayMs - 10 }, "간격: $g")
    }

    @Test
    fun `호스트가 다른 매장은 동시에 돈다`() {
        val startedAt = System.nanoTime()
        collector(
            FakeStore("a.example", "가", listOf("1지점", "2지점")),
            FakeStore("b.example", "나", listOf("1지점", "2지점")),
        ).collect(dates)
        val tookMs = (System.nanoTime() - startedAt) / 1_000_000

        // 순차면 12칸, 병렬이면 6칸이다
        assertEquals(12, hits.size)
        assertTrue(tookMs < delayMs * 10, "${tookMs}ms — 다른 호스트끼리 줄을 서고 있다")
    }

    @Test
    fun `브랜드가 달라도 서버가 같으면 한 줄로 선다`() {
        // 같은 예약 솔루션을 쓰는 두 브랜드가 한 도메인에 있을 수 있다.
        // 어댑터가 둘이라고 동시에 두드리면 그 서버에 두 배가 간다
        collector(
            FakeStore("same.example", "가", listOf("1지점")),
            FakeStore("same.example", "나", listOf("1지점")),
        ).collect(dates)

        val g = gaps("same.example")
        assertEquals(6, hits.size)
        assertTrue(g.all { it >= delayMs - 10 }, "간격: $g")
    }

    @Test
    fun `호스트가 동시 실행 수보다 많으면 파도로 쪼개진다`() {
        // 매장이 늘면 호스트도 늘어난다. 동시 실행 수가 그대로면 한 바퀴가 몇 배로 길어지는데,
        // **로그의 "한 바퀴 N초" 만 봐서는 원인이 안 보인다** — 그래서 여기서 못 박는다
        val nine = (1..9).map { FakeStore("h$it.example", "매장$it", listOf("1지점")) }

        collector(*nine.toTypedArray(), concurrency = 4).collect(dates)

        assertEquals(4, peakInFlight.get(), "동시 4로 묶어 뒀으면 9곳이 있어도 4개씩만 돈다")
    }

    @Test
    fun `동시 실행 수가 넉넉하면 호스트 전부가 같이 돈다`() {
        val nine = (1..9).map { FakeStore("h$it.example", "매장$it", listOf("1지점")) }

        // 호스트마다 한 줄씩이 정답이다. 속도 제한은 호스트별로 걸리므로(D13)
        // 호스트 수보다 많은 스레드는 아무것도 더 해 주지 않고, 적으면 서로 기다리기만 한다
        collector(*nine.toTypedArray(), concurrency = 16).collect(dates)

        assertEquals(9, peakInFlight.get(), "호스트가 9곳이면 9개가 같이 떠 있어야 한다")
    }

    @Test
    fun `요청 하나가 실패해도 나머지는 계속 돈다`() {
        val flaky = object : StoreAdapter {
            override val host = "c.example"
            override val brand = "다"
            override val branches = listOf(StoreRef("c-1", "다", "1지점"))
            override fun plan(dates: List<LocalDate>) = dates.mapIndexed { i, d ->
                FetchUnit("$i") {
                    if (i == 0) throw IllegalStateException("첫 요청 실패")
                    DaySchedule(StoreRef("c-1", "다", "1지점"), d, 7, 7, emptyList())
                }
            }
        }

        val summary = collector(flaky).collect(dates)

        assertEquals(1, summary.failures)
    }
}
