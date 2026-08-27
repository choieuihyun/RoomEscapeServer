package com.my_dream.server.crawler

import java.time.LocalDate
import java.time.LocalTime

/**
 * 어느 매장의 어느 지점인가. **매장마다 표현이 달라서** 공통 모양으로 옮겨 담는다.
 *
 * [key] 는 DB 의 매장 자연키이자 조회 API 의 `branch` 파라미터다 — 한 번 정하면 바꾸지 않는다.
 */
data class StoreRef(
    val key: String,
    val brand: String,
    val branchName: String,
)

/** 검증까지 끝난 크롤 결과. 지점 하나의 하루치 전 테마 시간표. */
data class DaySchedule(
    val store: StoreRef,
    val date: LocalDate,
    /** 사이트가 밝힌 예약 오픈 범위(일). 모르면 null */
    val reservationRangeDays: Int?,
    val themes: List<ThemeSchedule>,
)

data class ThemeSchedule(
    /** 사이트가 부여한 테마 ID. 이름이 바뀌어도 이게 같으면 같은 테마다 */
    val externalId: String?,
    val themeName: String,
    val posterUrl: String?,
    val genre: String?,
    val capacity: String?,
    val runningMinutes: Int?,
    /** 0.5 단위 실수다. 정수로 받으면 0.5 가 5 로 뒤집힌다 (아키텍처 D10) */
    val horrorLevel: Double?,
    val difficulty: Double?,
    val slots: List<Slot>,
)

data class Slot(
    val time: LocalTime,
    val available: Boolean,
)

/**
 * 파서가 페이지에서 읽어낸 그대로. **아직 요청한 날짜와 일치하는지 확인되지 않았다.**
 *
 * [renderedDate] 는 페이지가 스스로 밝힌 날짜다. 범위 밖 날짜를 요청하면 사이트가
 * 홈으로 보내거나 다른 날짜를 렌더하므로, 이 값을 요청값과 대조하는 것이 검증의 핵심이다.
 */
data class ParsedPage(
    val renderedDate: LocalDate?,
    val reservationRangeDays: Int?,
    val themes: List<ThemeSchedule>,
)
