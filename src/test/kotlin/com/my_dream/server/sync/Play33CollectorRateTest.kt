package com.my_dream.server.sync

import com.my_dream.server.crawler.play33.DaySchedule
import com.my_dream.server.crawler.play33.Play33Branch
import com.my_dream.server.crawler.play33.Play33Client
import com.my_dream.server.crawler.play33.Play33Crawler
import com.my_dream.server.crawler.play33.Play33Parser
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
 * 수집 속도가 **상대 서버 기준**으로 지켜지는지 (아키텍처 D13).
 *
 * 한 바퀴가 몇 초에 끝나는지는 여기서 볼 값이 아니다. 빨라지는 건 얼마든지 만들 수 있고,
 * 그게 곧 규칙 위반이다. 봐야 하는 건 **같은 서버로 초당 몇 번 갔는가** 하나다.
 */
@DataJpaTest
@Import(ScheduleSyncService::class, NotificationService::class, LoggingNotifier::class)
class Play33CollectorRateTest @Autowired constructor(
    private val sync: ScheduleSyncService,
    private val notifications: NotificationService,
) {

    /** 실제로 나가지 않고, 언제 어느 서버로 갔는지만 적어 둔다. */
    private class RecordingCrawler : Play33Crawler(Play33Client(), Play33Parser()) {
        private val lock = Any()
        val hits = mutableListOf<Pair<String, Long>>()

        override fun fetch(branch: Play33Branch, date: LocalDate): DaySchedule {
            synchronized(lock) { hits += branch.host to System.nanoTime() }
            return DaySchedule(branch = branch, date = date, reservationRangeDays = 7, themes = emptyList())
        }
    }

    private val delayMs = 60L

    private fun collectorWith(crawler: Play33Crawler, siteConcurrency: Int) =
        Play33Collector(crawler, sync, notifications, delayMs, siteConcurrency)

    @Test
    fun `모든 지점이 같은 서버를 쓴다`() {
        // D13 이 기대는 사실이다. 지점마다 서버가 다르면 병렬 정책을 다시 봐야 한다 —
        // 그때 이 테스트가 먼저 깨져서 알려 준다
        assertEquals(
            setOf("play33.kr"),
            Play33Branch.entries.map { it.host }.toSet(),
            "지점은 서버가 아니라 ?branch=N 파라미터다",
        )
    }

    @Test
    fun `같은 서버로 가는 요청은 겹치지 않고 간격을 지킨다`() {
        val crawler = RecordingCrawler()

        // 동시 4를 주지만, 사이트가 하나뿐이라 실제로는 한 줄로 서야 한다
        collectorWith(crawler, siteConcurrency = 4).collectAll()

        assertEquals(
            Play33Branch.entries.size * Play33Collector.RESERVATION_RANGE_DAYS,
            crawler.hits.size,
            "지점 × 7일이 전부 나가야 한다",
        )

        val gapsMs = crawler.hits.map { it.second }.sorted()
            .zipWithNext { a, b -> (b - a) / 1_000_000 }

        // 하나라도 간격 안에 들어오면 그 서버에 초당 1회를 넘긴 것이다.
        // 지점별로 병렬화했을 때가 정확히 이 경우였다 (28요청 8초 = 초당 3.5회)
        val tooFast = gapsMs.filter { it < delayMs - 10 }
        assertTrue(tooFast.isEmpty(), "간격이 ${delayMs}ms 미만인 요청 ${tooFast.size}건: $tooFast")
    }

    @Test
    fun `동시 설정을 올려도 같은 서버로는 빨라지지 않는다`() {
        val slow = RecordingCrawler()
        collectorWith(slow, siteConcurrency = 1).collectAll()

        val fast = RecordingCrawler()
        collectorWith(fast, siteConcurrency = 16).collectAll()

        // 사이트가 하나면 동시 설정이 아무 영향을 주면 안 된다.
        // 영향이 있다면 병렬 단위가 사이트가 아니라 지점으로 돌아간 것이다
        fun span(c: RecordingCrawler) = (c.hits.maxOf { it.second } - c.hits.minOf { it.second }) / 1_000_000
        val expected = (slow.hits.size - 1) * delayMs

        assertTrue(span(slow) >= expected - 50, "동시 1: ${span(slow)}ms")
        assertTrue(span(fast) >= expected - 50, "동시 16 인데 ${span(fast)}ms — 서버 하나에 몰아친다")
    }
}
