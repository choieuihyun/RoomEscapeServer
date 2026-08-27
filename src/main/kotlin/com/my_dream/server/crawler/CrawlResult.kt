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
