package com.my_dream.server.sync

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.DayOfWeek
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
 *   전부    돌아가며 하나   8일치를 하나씩 도니 한 날짜당 8바퀴마다
 * ```
 *
 * **한 바퀴는 5분이 아니다.** `fixedDelay` 라 "이전 바퀴가 **끝난 뒤** 5분" 이고,
 * 수집 자체가 2.3~2.8분 걸린다 — 실측 2026-09-01 은 436~471초다.
 * 그래서 **주기를 분으로 적지 않는다.** 매장이 늘면 또 틀린다.
 *
 * **먼 창을 따로 둔 이유 (2026-08-31)** — 지구별 대구점은 예약이 **2주치** 열리는데
 * 나머지 지점은 1주치다. 창을 통째로 15일로 넓히면 위 규칙이 15일 위에서 돌아
 * **주말이 2개 → 4개, 평일 순환이 4바퀴 → 8바퀴** 이 된다.
 * 지점 하나 때문에 **모든 매장의 가까운 날짜가 느려진다.** 그건 손해다 —
 * 취소는 임박한 날짜에 몰린다. 그래서 넓히지 않고 **칸을 하나 더 만든다.**
 *
 * ### 바퀴 번호는 여기서 만들지 않는다 ([SweepTicker] 가 준다)
 *
 * 이 클래스는 **번호를 넣으면 날짜가 나오는 순수 함수**다. DB 도 시계도 모른다.
 * 그래야 "3번째 바퀴에 무엇을 보나" 를 그냥 부르면서 테스트할 수 있다.
 *
 * ⚠️ **다만 그 순수함이 2026-09-01 버그를 숨겼다.** 아래 순환은 전부
 * **번호가 1씩 오른다**는 전제 위에 서 있는데(나머지 연산), 실제로 들어오던 번호는
 * 시계에서 나와 1~2씩 뛰고 있었다. 테스트는 0,1,2,3 을 손으로 넣었으니 영원히 통과했다.
 *
 * **순수 함수를 테스트하는 것으로는 "실제로 무엇이 들어오는가" 를 못 잡는다.**
 * 그래서 번호를 만드는 쪽([SweepTicker])에 따로 테스트를 뒀다.
 */
@Component
class PollingSchedule(
    @param:Value("\${collector.range-days:7}") private val rangeDays: Int,
    @param:Value("\${collector.far-range-days:15}") private val farRangeDays: Int,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun datesForSweep(sweep: Long, today: LocalDate = LocalDate.now()): List<LocalDate> {
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
            // 매 바퀴 볼 이유가 없어서다 — 그때쯤 나는 취소는 급하지 않다
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
