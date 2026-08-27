package com.my_dream.server.crawler.play33

import com.my_dream.server.crawler.ThemeSchedule
import java.time.LocalDate

/**
 * 파서가 HTML 에서 읽어낸 그대로. 아직 요청한 날짜와 일치하는지 확인되지 않았다.
 *
 * [renderedDate] 는 페이지가 스스로 밝힌 날짜(`input[name=date]` 의 value)다.
 * 범위 밖 날짜를 요청하면 홈으로 302 되므로, 이 값이 없으면 예약 페이지를 못 받은 것이다.
 *
 * `DaySchedule` · `ThemeSchedule` · `Slot` 은 매장 공통이라 `crawler` 패키지로 옮겼다.
 * 이것만 플레이33 파서 전용이라 여기 남는다.
 */
data class ParsedPage(
    val renderedDate: LocalDate?,
    val reservationRangeDays: Int?,
    val themes: List<ThemeSchedule>,
)
