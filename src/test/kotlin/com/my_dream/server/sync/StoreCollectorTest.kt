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

    /** 요청 1회를 흉내낸다. **속도 제한을 통과해서** 나간 시각을 적는다 */
    private inner class FakeStore(
        override val host: String,
        override val brand: String,
        private val branches: List<String>,
    ) : StoreAdapter {
        override fun plan(dates: List<LocalDate>) = branches.flatMap { b ->
            dates.map { d ->
                FetchUnit("$b $d") {
                    limiter.throttled(host) {
                        synchronized(hits) { hits += host to System.nanoTime() / 1_000_000 }
                        DaySchedule(StoreRef("$brand-$b", brand, b), d, 7, emptyList())
                    }
                }
            }
        }
    }

    private fun collector(vararg adapters: StoreAdapter, concurrency: Int = 4) =
        StoreCollector(adapters.toList(), ingest, concurrency)

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
    fun `요청 하나가 실패해도 나머지는 계속 돈다`() {
        val flaky = object : StoreAdapter {
            override val host = "c.example"
            override val brand = "다"
            override fun plan(dates: List<LocalDate>) = dates.mapIndexed { i, d ->
                FetchUnit("$i") {
                    if (i == 0) throw IllegalStateException("첫 요청 실패")
                    DaySchedule(StoreRef("c-1", "다", "1지점"), d, 7, emptyList())
                }
            }
        }

        val summary = collector(flaky).collect(dates)

        assertEquals(1, summary.failures)
    }
}
