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
 * 토·일   매 바퀴        주말은 예약이 빡세고, 풀리면 몇 분 안에 채간다
 * 금      2바퀴마다
 * 그 외   돌아가며 하나   널널해서 취소표 자체가 드물고, 나도 덜 급하다
 * ```
 *
 * **바퀴 번호를 시계에서 뽑는다.** 카운터를 들고 있으면 서버를 재시작할 때마다 0 으로 돌아가서,
 * 개발 중에 자주 껐다 켜면 같은 평일만 계속 보고 나머지는 굶는다.
 */
@Component
class PollingSchedule(
    @param:Value("\${collector.range-days:7}") private val rangeDays: Int,
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

        val picked = buildList {
            addAll(weekend)
            if (sweep % FRIDAY_EVERY == 0L) addAll(friday)
            // 평일은 한 바퀴에 하나씩 돌아가며. 굶는 날짜가 없게 나머지 연산으로 순환한다
            if (rest.isNotEmpty()) add(rest[Math.floorMod(sweep, rest.size.toLong()).toInt()])
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
