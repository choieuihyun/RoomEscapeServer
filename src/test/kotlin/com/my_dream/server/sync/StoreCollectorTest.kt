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
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.ResourceAccessException
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 한 바퀴가 **사이트 단위로** 병렬이고, 같은 사이트로는 줄을 서는지 (아키텍처 D13).
 */
@DataJpaTest
@Import(ScheduleSyncService::class, NotificationService::class, LoggingNotifier::class, ScheduleIngest::class)
class StoreCollectorTest @Autowired constructor(
    private val ingest: ScheduleIngest,
    // collectAll() 을 안 쓰는 테스트지만 생성자에 필요하다. 바퀴 번호는 여기서 검증하지 않는다
    private val counters: SweepCounterRepository,
) {

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
                FetchUnit("$b $d", "$brand-$b", d) {
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

    private fun collector(vararg adapters: StoreAdapter) =
        StoreCollector(
            adapters.toList(), ingest, PollingSchedule(rangeDays = 7, farRangeDays = 7),
            SweepTicker(counters),
        )

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
    fun `호스트가 몇 개든 전부 같이 돈다 — 상한이 없다`() {
        // 예전엔 동시 16 으로 묶어서, 호스트가 그보다 많으면 바퀴가 파도로 쪼개졌다 —
        // 로그의 "한 바퀴 N초" 만 봐서는 원인이 안 보이는 종류다 (D18 → D40).
        // 호스트마다 한 줄씩이 정답이다. 속도 제한은 호스트별로 걸리므로(D13) 더 많으면 할 일이 없다
        val many = (1..24).map { FakeStore("h$it.example", "매장$it", listOf("1지점")) }

        collector(*many.toTypedArray()).collect(dates)

        assertEquals(24, peakInFlight.get(), "호스트가 24곳이면 24개가 같이 떠 있어야 한다")
    }

    @Test
    fun `요청 하나가 실패해도 나머지는 계속 돈다`() {
        val flaky = object : StoreAdapter {
            override val host = "c.example"
            override val brand = "다"
            override val branches = listOf(StoreRef("c-1", "다", "1지점"))
            override fun plan(dates: List<LocalDate>) = dates.mapIndexed { i, d ->
                FetchUnit("$i", "c-1", d) {
                    if (i == 0) throw IllegalStateException("첫 요청 실패")
                    DaySchedule(StoreRef("c-1", "다", "1지점"), d, 7, 7, emptyList())
                }
            }
        }

        val summary = collector(flaky).collect(dates)

        assertEquals(1, summary.failures)
    }

    /** 지점 하나 · 날짜마다 요청 하나. [fail] 이 던지면 그 요청은 실패한다. **보낸 요청 수**를 센다 */
    private inner class Scripted(override val host: String, private val fail: (Int) -> Exception?) : StoreAdapter {
        val sent = AtomicInteger()
        override val brand = host
        override val branches = listOf(StoreRef("$host-1", host, "1지점"))
        override fun plan(dates: List<LocalDate>) = dates.mapIndexed { i, d ->
            FetchUnit("$host $d", "$host-1", d) {
                limiter.throttled(host) {
                    sent.incrementAndGet()
                    fail(i)?.let { throw it }
                    DaySchedule(StoreRef("$host-1", host, "1지점"), d, 7, 7, emptyList())
                }
            }
        }
    }

    private val five = (0..4).map { LocalDate.now().plusDays(it.toLong()) }

    @Test
    fun `429 를 받으면 그 호스트의 남은 요청은 안 보내고, 다른 호스트는 그대로 돈다`() {
        val angry = Scripted("angry.example") { i -> if (i == 1) HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS) else null }
        val calm = Scripted("calm.example") { null }

        val summary = collector(angry, calm).collect(five)

        assertEquals(2, angry.sent.get(), "429 뒤에도 요청이 나갔다")
        assertEquals(5, calm.sent.get(), "다른 호스트까지 멈췄다")
        assertEquals(1, summary.failures, "429 하나만 실패다")
        assertEquals(3, summary.skipped, "보내지 않은 것은 실패가 아니라 따로 센다")
    }

    @Test
    fun `쉬는 중인 호스트는 다음 바퀴에도 한 건도 안 보낸다`() {
        val angry = Scripted("angry.example") { i -> if (i == 0) HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS) else null }
        val c = collector(angry)
        c.collect(five)
        val afterFirst = angry.sent.get()

        val second = c.collect(five)

        assertEquals(afterFirst, angry.sent.get(), "멈춤이 다음 바퀴로 안 이어졌다")
        assertEquals(5, second.skipped)
        assertEquals(0, second.failures)
    }

    @Test
    fun `응답을 못 받으면 그 바퀴의 남은 요청을 접는다 — 멈춤은 걸지 않는다`() {
        val down = Scripted("down.example") { i -> if (i == 0) ResourceAccessException("Read timed out") else null }
        val c = collector(down)

        val first = c.collect(five)
        assertEquals(1, down.sent.get(), "타임아웃 뒤에도 같은 호스트에 계속 물었다")
        assertEquals(4, first.skipped)

        // 타임아웃은 "그만" 이 아니다 — 다음 바퀴에는 다시 물어본다
        c.collect(five)
        assertTrue(down.sent.get() > 1, "타임아웃 한 번으로 호스트를 쉬게 했다")
    }

    @Test
    fun `파싱 실패로는 접지 않는다`() {
        // 우리 쪽 판정 실패다. 같은 호스트의 다른 날짜는 멀쩡할 수 있다
        val odd = Scripted("odd.example") { i -> if (i == 0) IllegalStateException("날짜 대조 실패") else null }

        val summary = collector(odd).collect(five)

        assertEquals(5, odd.sent.get())
        assertEquals(1, summary.failures)
        assertEquals(0, summary.skipped)
    }
}
