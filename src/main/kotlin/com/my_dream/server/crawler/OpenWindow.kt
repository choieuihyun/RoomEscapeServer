package com.my_dream.server.crawler

import java.time.LocalDate

/**
 * **예약이 며칠치 열려 있는지는 지점마다 다르다.** 매장 단위가 아니다.
 *
 * 2026-08-31 에 사용자 제보로 알고 실측했다 — 지구별은 **대구만 2주**고 홍대 두 곳은 1주다.
 * 같은 브랜드, 같은 사이트, 같은 마크업인데 창만 다르다.
 *
 * ```
 * 지구별 대구        +14 열림 · +15 닫힘   →  openDays 15
 * 지구별 홍대 두 곳   +6  열림 · +7  닫힘   →  openDays 7
 * ```
 *
 * **왜 어댑터가 거르나 —** 수집기는 "이번 바퀴에 볼 날짜" 를 한 벌만 만든다(D14).
 * 지점마다 창이 다르니 **누가 그 날짜를 실제로 물을지는 어댑터가 정해야 한다.**
 * `StoreAdapter.plan(dates)` 가 이미 "어떤 요청이 필요한지 내놔라" 는 모양이라
 * **인터페이스를 안 바꾸고 여기서 끝난다** (D15).
 *
 * **못 걸러도 조용히 틀리지는 않는다.** 창 밖 날짜를 물으면 사이트가 302 로 홈에 보내고,
 * 크롤러가 날짜 대조에서 예외로 끊는다(D2). 실패가 시끄럽게 쌓이지 조용히 비지 않는다.
 * 그래서 이 필터는 **안전장치가 아니라 잡음을 없애는 장치**다.
 */
fun List<LocalDate>.openWithin(openDays: Int, today: LocalDate = LocalDate.now()): List<LocalDate> {
    val last = today.plusDays((openDays - 1).toLong())
    return filter { !it.isAfter(last) }
}

/**
 * **창의 마지막 날인가.** 매일 하루씩 예약을 여는 사이트에서 이 날짜는
 * 그날 오픈 시각이 지나기 전까지 회차가 0개로 온다 — 정상이다 (아키텍처 D22).
 *
 * 여기가 하루 어긋나면 **매일 지점 수만큼 헛경고가 뜨거나**(너무 좁게 잡으면),
 * **진짜 빈 날짜를 정상으로 넘긴다**(너무 넓게 잡으면).
 */
fun LocalDate.isLastOpenDay(openDays: Int, today: LocalDate): Boolean =
    this == today.plusDays((openDays - 1).toLong())
