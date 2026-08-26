package com.my_dream.server.crawler.play33

import java.time.LocalDate
import java.time.LocalTime

/** 검증까지 끝난 크롤 결과. 지점 하나의 하루치 전 테마 시간표. */
data class DaySchedule(
    val branch: Play33Branch,
    val date: LocalDate,
    val reservationRangeDays: Int?,
    val themes: List<ThemeSchedule>,
)

/**
 * 파서가 HTML 에서 읽어낸 그대로. 아직 요청한 날짜와 일치하는지 확인되지 않았다.
 *
 * [renderedDate] 는 페이지가 스스로 밝힌 날짜(`input[name=date]` 의 value)다.
 * 범위 밖 날짜를 요청하면 홈으로 302 되므로, 이 값이 없으면 예약 페이지를 못 받은 것이다.
 */
data class ParsedPage(
    val renderedDate: LocalDate?,
    val reservationRangeDays: Int?,
    val themes: List<ThemeSchedule>,
)

data class ThemeSchedule(
    /** 사이트가 부여한 테마 ID(`<option value="18">`). 이름이 바뀌어도 이게 같으면 같은 테마다 */
    val externalId: String?,
    val themeName: String,
    val posterUrl: String?,
    val genre: String?,
    val capacity: String?,
    val runningMinutes: Int?,
    val horrorLevel: Int?,
    val difficulty: Int?,
    val slots: List<Slot>,
)

data class Slot(
    val time: LocalTime,
    val available: Boolean,
)
