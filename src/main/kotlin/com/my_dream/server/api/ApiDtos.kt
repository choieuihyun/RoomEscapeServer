package com.my_dream.server.api

import java.time.Instant
import java.time.LocalDate

/**
 * 조회 API 응답. **Floduler 가 그리는 화면 순서에 맞춘 모양이다** — 지점 → 날짜 → 테마 선택.
 * 계약 원본은 Floduler 저장소의 `작업명세서.md` §4.4. 바꾸려면 양쪽을 같이 본다.
 */
data class BranchDto(
    val id: String,
    val store: String,
    val branch: String,
    /** 고를 수 있는 날짜. 비어 있으면 아직 수집된 적이 없는 지점이다 */
    val dates: List<LocalDate>,
    /** 마지막으로 확인한 시각. null 이면 아직 수집된 적이 없다 */
    val checkedAt: Instant?,
)

data class ScheduleDto(
    val store: String,
    val branch: String,
    val date: LocalDate,
    /** 이 응답이 언제 기준인지. 화면에 "N분 전 기준"으로 보여주라고 있는 값이다 */
    val checkedAt: Instant?,
    val themes: List<ThemeDto>,
)

data class ThemeDto(
    /** `play33-konkuk:18` — 테마를 가리키는 안정적인 키. 선택 상태 유지와 (나중에) 감시 등록에 쓴다 */
    val id: String,
    val name: String,
    /** 매장 간 이동시간(Floduler F-13)이 쓰는 값. 같은 지점끼리는 문자열이 같아 이동시간 0 이 된다 */
    val place: String,
    /** 홈페이지에 적힌 소요시간을 그대로. 회차 간격으로 보정하지 않는다 */
    val dur: Int?,

    // --- 테마 고를 때 쓰라고 딸려 보내는 것들 ---
    val genre: String?,
    /** 사이트 표기 그대로. 예: `2~3인` */
    val capacity: String?,
    /** [capacity] 에서 뽑아낸 값. 인원이 안 맞는 테마를 걸러내라고 있다 */
    val minPeople: Int?,
    val maxPeople: Int?,
    val horrorLevel: Double?,
    /** 별 개수. **0.5 단위** 다 — `2.5` 가 실제로 온다 */
    val difficulty: Double?,
    val posterUrl: String?,

    val sessions: List<SessionDto>,
)

/**
 * [t] 는 자정부터의 분. Floduler `session.t` 가 이미 이 형식이다.
 *
 * **매진 회차도 빠짐없이 포함한다.** 계산에서 빼는 것과 화면에서 지우는 것은 다르다 —
 * 화면에 남아 있어야 나중에 "이 자리 감시" 를 붙일 수 있다.
 */
data class SessionDto(
    /** 이 자리를 가리키는 값. 감시를 걸 때 `POST /api/watches` 에 그대로 넣는다 */
    val id: Long,
    val t: Int,
    val soldout: Boolean,
)
