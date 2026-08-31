package com.my_dream.server.sync

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate

/**
 * 이번 바퀴에 **어느 날짜를 긁을지** 정한다 (아키텍처 D14).
 *
 * 모든 날짜를 똑같이 자주 볼 필요가 없다. 취소표는 **늦게 알면 소용없는 자리**가 따로 있다.
 *
 * ```
 * 가까운 창 (오늘~+6)
 *   토·일   매 바퀴        주말은 예약이 빡세고, 풀리면 몇 분 안에 채간다
 *   금      2바퀴마다
 *   그 외   돌아가며 하나   널널해서 취소표 자체가 드물고, 나도 덜 급하다
 * 먼 창 (+7~+14)
 *   전부    돌아가며 하나   8일치를 하나씩 도니 한 날짜당 약 40분마다
 * ```
 *
 * **먼 창을 따로 둔 이유 (2026-08-31)** — 지구별 대구점은 예약이 **2주치** 열리는데
 * 나머지 지점은 1주치다. 창을 통째로 15일로 넓히면 위 규칙이 15일 위에서 돌아
 * **주말이 2개 → 4개, 평일 순환이 4 → 8(20분 → 40분)** 이 된다.
 * 지점 하나 때문에 **모든 매장의 가까운 날짜가 느려진다.** 그건 손해다 —
 * 취소는 임박한 날짜에 몰린다. 그래서 넓히지 않고 **칸을 하나 더 만든다.**
 *
 * **바퀴 번호를 시계에서 뽑는다.** 카운터를 들고 있으면 서버를 재시작할 때마다 0 으로 돌아가서,
 * 개발 중에 자주 껐다 켜면 같은 평일만 계속 보고 나머지는 굶는다.
 */
@Component
class PollingSchedule(
    @param:Value("\${collector.range-days:7}") private val rangeDays: Int,
    @param:Value("\${collector.far-range-days:15}") private val farRangeDays: Int,
    @param:Value("\${collector.interval-ms:300000}") private val intervalMs: Long,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun datesFor(today: LocalDate = LocalDate.now(), now: Instant = Instant.now()): List<LocalDate> =
        datesForSweep(sweepIndex(now), today)

    /** 지금이 몇 번째 바퀴인가. 시계에서 뽑으므로 재시작해도 이어진다 */
    fun sweepIndex(now: Instant = Instant.now()): Long =
        now.toEpochMilli() / intervalMs.coerceAtLeast(1)

    fun datesForSweep(sweep: Long, today: LocalDate): List<LocalDate> {
        val window = (0 until rangeDays).map { today.plusDays(it.toLong()) }
        val weekend = window.filter { it.dayOfWeek == DayOfWeek.SATURDAY || it.dayOfWeek == DayOfWeek.SUNDAY }
        val friday = window.filter { it.dayOfWeek == DayOfWeek.FRIDAY }
        val rest = window.filter { it.dayOfWeek !in WEEKEND_AND_FRIDAY }

        // 가까운 창 밖. 여기까지 여는 지점은 2026-08-31 기준 지구별 대구뿐이고,
        // 나머지 지점은 어댑터가 각자 걸러 낸다 (StoreAdapter.plan). 그래서 한 바퀴에 요청이
        // **딱 1건** 늘어난다 — 못 거른 지점은 302 를 받아 예외로 끊기므로 조용히 틀리지 않는다
        val far = (rangeDays until farRangeDays).map { today.plusDays(it.toLong()) }

        val picked = buildList {
            addAll(weekend)
            if (sweep % FRIDAY_EVERY == 0L) addAll(friday)
            // 평일은 한 바퀴에 하나씩 돌아가며. 굶는 날짜가 없게 나머지 연산으로 순환한다
            if (rest.isNotEmpty()) add(rest[Math.floorMod(sweep, rest.size.toLong()).toInt()])
            // 먼 날짜도 같은 방식으로 하나씩. 요일을 안 가리는 이유는 2주 뒤 주말이라고
            // 5분마다 볼 이유가 없어서다 — 그때쯤 나는 취소는 급하지 않다
            if (far.isNotEmpty()) add(far[Math.floorMod(sweep, far.size.toLong()).toInt()])
        }.sorted()

        log.debug("{}번째 바퀴 — {}일치 {}", sweep, picked.size, picked)
        return picked
    }

    companion object {
        /** 금요일을 몇 바퀴마다 볼지. 주말 다음으로 빡세다 */
        const val FRIDAY_EVERY = 2L
        private val WEEKEND_AND_FRIDAY = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY, DayOfWeek.FRIDAY)
    }
}
