package com.my_dream.server.sync

import com.my_dream.server.crawler.DaySchedule
import com.my_dream.server.crawler.StoreRef
import com.my_dream.server.domain.StoreRepository
import com.my_dream.server.domain.ThemeRepository
import com.my_dream.server.domain.TimeSlotRepository
import com.my_dream.server.notify.LoggingNotifier
import com.my_dream.server.notify.NotificationService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **저장 문** — 수집 스레드가 아무리 많아도 한 번에 저장하는 수는 [ScheduleIngest] 의 문을 안 넘는다 (아키텍처 D40).
 *
 * 이게 깨지면 호스트 100개짜리 바퀴가 DB 커넥션 100개를 달라고 한다. 풀을 다 쓰는 동안 조회 API 가 기다리는데,
 * 증상이 "가끔 느리다" 뿐이라 운영에서는 원인을 못 찾는다.
 */
@DataJpaTest
@Import(NotificationService::class, LoggingNotifier::class)
class ScheduleIngestGateTest @Autowired constructor(
    private val stores: StoreRepository,
    private val themes: ThemeRepository,
    private val slots: TimeSlotRepository,
    private val notifications: NotificationService,
) {

    private val inFlight = AtomicInteger()
    private val peak = AtomicInteger()

    /** 저장을 흉내낸다 — DB 는 안 건드리고, 동시에 몇 개가 안에 있었는지만 잰다 */
    private inner class CountingSync : ScheduleSyncService(stores, themes, slots) {
        override fun sync(day: DaySchedule): SyncResult {
            val now = inFlight.incrementAndGet()
            peak.updateAndGet { maxOf(it, now) }
            try {
                Thread.sleep(40)
            } finally {
                inFlight.decrementAndGet()
            }
            return SyncResult(emptyList(), emptyList())
        }
    }

    private fun day(i: Int) = DaySchedule(StoreRef("s$i", "매장$i", "1지점"), LocalDate.now(), 7, 7, emptyList())

    /** 서로 다른 지점 [n] 곳을 동시에 저장한다 — 지점 잠금은 지점마다 따로라 문만이 수를 묶는다 */
    private fun saveAtOnce(ingest: ScheduleIngest, n: Int) {
        val pool = Executors.newFixedThreadPool(n)
        val start = CountDownLatch(1)
        repeat(n) { i -> pool.submit { start.await(); ingest.ingest(day(i)) } }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))
    }

    @Test
    fun `스레드가 20개여도 동시에 저장하는 것은 문 크기까지다`() {
        saveAtOnce(ScheduleIngest(CountingSync(), notifications, saveConcurrency = 4, dbPoolSize = 24), 20)

        assertEquals(4, peak.get(), "문이 4인데 ${peak.get()}개가 동시에 저장했다")
    }

    @Test
    fun `문이 넉넉하면 막지 않는다`() {
        // 문이 속도를 깎으면 안 된다 — 호스트 수보다 크면 전부 같이 들어간다
        saveAtOnce(ScheduleIngest(CountingSync(), notifications, saveConcurrency = 16, dbPoolSize = 24), 8)

        assertEquals(8, peak.get())
    }
}
