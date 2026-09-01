package com.my_dream.server.sync

import com.my_dream.server.crawler.HostRateLimiter
import com.my_dream.server.crawler.jigubyeol.JigubyeolAdapter
import com.my_dream.server.crawler.jigubyeol.JigubyeolClient
import com.my_dream.server.crawler.jigubyeol.JigubyeolCrawler
import com.my_dream.server.crawler.jigubyeol.JigubyeolParser
import com.my_dream.server.crawler.isLastOpenDay
import com.my_dream.server.crawler.openWithin
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **먼 창**(2026-08-31 추가). 지구별 대구점만 예약이 2주치 열려서,
 * 창을 통째로 넓히지 않고 칸을 하나 더 만들었다.
 *
 * 여기서 지키려는 것은 셋이다.
 * 1. 먼 창을 켜도 **가까운 날짜를 보는 주기가 그대로**여야 한다 (그게 넓히지 않은 이유다)
 * 2. 먼 날짜가 **굶지 않아야** 한다
 * 3. 창이 좁은 지점은 먼 날짜를 **아예 안 물어야** 한다 (물으면 302 → 실패로 쌓인다)
 */
class FarWindowTest {

    /** 2026-08-31 은 월요일 */
    private val monday = LocalDate.of(2026, 8, 31)

    private val 예전 = PollingSchedule(rangeDays = 7, farRangeDays = 7)
    private val 지금 = PollingSchedule(rangeDays = 7, farRangeDays = 15)

    @Test
    fun `먼 창을 켜도 가까운 날짜 선택은 한 글자도 안 바뀐다`() {
        // **이게 이 설계의 전부다.** range-days 를 15 로 올렸다면 주말이 2개 → 4개,
        // 평일 순환이 4 → 8(20분 → 40분)이 되어 **모든 매장이 느려졌을 것이다.**
        // 지점 하나 때문에 전체가 느려지면 안 된다 — 취소는 임박한 날짜에 몰린다
        val 가까운끝 = monday.plusDays(6)
        repeat(24) { n ->
            val before = 예전.datesForSweep(n.toLong(), monday)
            val after = 지금.datesForSweep(n.toLong(), monday).filter { !it.isAfter(가까운끝) }
            assertEquals(before, after, "$n 번째 바퀴에서 가까운 창이 달라졌다")
        }
    }

    @Test
    fun `먼 날짜는 한 바퀴에 딱 하나만 늘어난다`() {
        // 요청이 얼마나 느는지가 이 값으로 정해진다. 하나면 하루 288건이고,
        // 먼 창을 통째로 넣었다면 8배가 된다
        repeat(24) { n ->
            val before = 예전.datesForSweep(n.toLong(), monday).size
            val after = 지금.datesForSweep(n.toLong(), monday).size
            assertEquals(before + 1, after, "$n 번째 바퀴")
        }
    }

    @Test
    fun `먼 날짜가 굶지 않는다 — 여덟 바퀴면 한 바퀴씩 다 본다`() {
        val 먼날짜 = (7 until 15).map { monday.plusDays(it.toLong()) }.toSet()
        val 본것 = (0 until 8).flatMap { 지금.datesForSweep(it.toLong(), monday) }.toSet()

        assertEquals(먼날짜, 본것.intersect(먼날짜), "8바퀴 안에 못 본 먼 날짜가 있다")
    }

    @Test
    fun `창이 좁은 지점은 먼 날짜를 아예 안 묻는다`() {
        // 대구만 2주다. 어드벤처·라스트시티에 먼 날짜를 물으면 302 가 오고
        // 크롤러가 예외로 끊어 **매 바퀴 실패가 2건씩 쌓인다** — 진짜 고장이 묻히는 자리다
        val adapter = JigubyeolAdapter(
            JigubyeolCrawler(JigubyeolClient(HostRateLimiter(1200)), JigubyeolParser()),
        )
        val 먼날짜 = LocalDate.now().plusDays(10)

        val units = adapter.plan(listOf(LocalDate.now(), 먼날짜)).map { it.label }

        // 가까운 날짜는 세 지점 다, 먼 날짜는 대구만
        assertEquals(4, units.size, units.toString())
        assertEquals(1, units.count { it.endsWith(" $먼날짜") }, units.toString())
        assertTrue(units.any { it == "대구점 $먼날짜" }, units.toString())
    }

    @Test
    fun `창의 마지막 날을 정확히 짚는다 — 비트포비아 오픈 전 판정의 근거다`() {
        // 매일 하루씩 여는 사이트는 **창의 마지막 날**이 오픈 시각 전까지 회차 0개다 (D22).
        // 여기가 하루 어긋나면 둘 중 하나가 된다 —
        //   좁게 잡으면 매일 지점 수만큼 헛경고, 넓게 잡으면 진짜 빈 날짜를 정상으로 넘긴다
        assertTrue(monday.plusDays(6).isLastOpenDay(7, monday))
        assertTrue(!monday.plusDays(5).isLastOpenDay(7, monday), "마지막 전날은 아니다")
        assertTrue(!monday.plusDays(7).isLastOpenDay(7, monday), "창 밖은 마지막 날이 아니다")
        assertTrue(monday.plusDays(14).isLastOpenDay(15, monday), "대구는 15일치")
    }

    @Test
    fun `openWithin 은 경계 날짜를 포함한다`() {
        // openDays 는 **오늘을 포함한 일수**다. 15 면 오늘..오늘+14 다 —
        // 여기서 하루가 어긋나면 대구가 매 바퀴 302 를 한 건씩 받는다
        val dates = (0..15).map { monday.plusDays(it.toLong()) }

        assertEquals(monday.plusDays(14), dates.openWithin(15, monday).last())
        assertEquals(monday.plusDays(6), dates.openWithin(7, monday).last())
        assertEquals(listOf(monday), dates.openWithin(1, monday))
    }

    @Test
    fun `바퀴 번호가 1씩 오르지 않으면 먼 날짜가 굶는다`() {
        // **운영에서 실제로 본 증상이다** (2026-09-01 · 8바퀴 · 53분).
        //   먼 창 8일 중 09-08 · 09-13 을 한 번도 안 봤고, 09-09 · 09-12 는 두 번씩 봤다.
        //
        // 위 `먼 날짜도 굶지 않는다` 테스트는 번호를 0..7 로 **손으로 넣어서** 통과했다.
        // 번호가 균등하다는 것이 검증 대상이 아니라 전제였고, 그 전제가 깨져 있었다.
        // 여기서는 **실제로 들어오던 번호**를 흉내내서 그 차이를 박아 둔다.
        val 먼창 = (7..14).map { monday.plusDays(it.toLong()) }.toSet()

        fun 여덟바퀴에_본_먼날짜(번호: (Int) -> Long) =
            (0 until 8).flatMap { 지금.datesForSweep(번호(it), monday) }.toSet() intersect 먼창

        assertEquals(
            먼창, 여덟바퀴에_본_먼날짜 { it.toLong() },
            "1씩 오르면 8바퀴에 먼 창 8일을 정확히 한 번씩 다 본다",
        )

        // 시계 나누기(470초를 300초로) 를 흉내낸다
        val 굶은날 = 먼창 - 여덟바퀴에_본_먼날짜 { (it * 470L * 1000) / 300_000 }
        assertTrue(
            굶은날.isNotEmpty(),
            "번호가 1~2 씩 뛰면 8바퀴가 지나도 안 본 날짜가 남는다 — 굶는 날짜는 스스로 회복되지 않는다",
        )
    }
}
