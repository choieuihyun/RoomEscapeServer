package com.my_dream.server.crawler

import java.time.LocalDate

/**
 * 매장 하나를 긁는 법. **매장마다 요청 모양이 다르다** (아키텍처 D15).
 *
 * ```
 * 플레이33     지점 × 날짜 = 요청 1회   HTML   disabled 속성
 * 래빗홀       지점 × 날짜 = 요청 1회   HTML   label 존재 여부
 * 키이스케이프  테마 × 날짜 = 요청 1회   JSON   enable Y/N
 * ```
 *
 * 그래서 "이 날짜들을 긁어라" 가 아니라 **"어떤 요청들이 필요한지 목록으로 내놔라"** 로 받는다.
 * 속도 조절은 요청 단위로 [HostRateLimiter] 가 한다.
 */
interface StoreAdapter {

    /**
     * 실제로 두드리는 서버. **병렬 단위이자 속도 상한**이다 (아키텍처 D13).
     *
     * 지점이 여럿이어도 서버가 하나면 값이 같다 — 플레이33 4지점이 전부 `play33.kr` 이다.
     */
    val host: String

    /** `플레이33` 처럼 사람이 읽는 이름. 로그에만 쓴다 */
    val brand: String

    /** 이 날짜들을 긁으려면 어떤 요청이 필요한가. */
    fun plan(dates: List<LocalDate>): List<FetchUnit>
}

/**
 * 수집 작업 하나. **HTTP 요청 정확히 1회**를 뜻한다.
 *
 * 하나 안에서 여러 번 요청하면 속도 규칙이 구조가 아니라 관례가 된다 —
 * 실제로 [HostRateLimiter] 가 요청마다 걸리므로 규칙이 깨지지는 않지만,
 * 한 바퀴가 몇 요청인지 미리 셀 수 없게 되어 주기 계산이 어긋난다.
 */
class FetchUnit(
    /** 실패 로그에 쓸 이름. 예: `건대점 2026-08-28` */
    val label: String,
    private val body: () -> DaySchedule,
) {
    fun fetch(): DaySchedule = body()
}
