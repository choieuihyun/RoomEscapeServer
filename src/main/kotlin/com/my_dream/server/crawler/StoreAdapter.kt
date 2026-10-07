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

    /** `플레이33` 처럼 사람이 읽는 이름 */
    val brand: String

    /**
     * 이 매장이 지원하는 지점 전부.
     *
     * **아직 한 번도 수집 안 된 지점도 여기 나온다.** 조회 API 가 "지원하지만 아직 못 본 지점" 과
     * "그런 지점 없음" 을 구분해 답할 수 있어야 해서다.
     */
    val branches: List<StoreRef>

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
    /** 이 요청이 답해 주는 지점. [StoreRef.key] 와 같은 값이다 */
    val storeKey: String,
    val date: LocalDate,
    /**
     * **테마마다 따로 묻는 매장만** 채운다 (키이스케이프 · 제로월드). `null` 이면 지점 전체가 온다.
     *
     * 크롤러가 `ThemeSchedule.externalId` 에 넣는 값과 **같은 식**이어야 한다 —
     * 그 값이 DB 의 `theme.external_id` 가 되고, 감시 빠른 확인이 그것으로 요청을 고른다.
     * 어긋나면 그 테마의 감시는 빠른 확인에서 조용히 빠진다 (그래서 못 찾으면 경고를 낸다).
     */
    val themeExternalId: String? = null,
    private val body: () -> DaySchedule,
) {
    fun fetch(): DaySchedule = body()

    /**
     * 이 요청 하나로 그 자리를 확인할 수 있는가 (아키텍처 D27).
     *
     * **라벨로 고르지 않는다.** 라벨은 사람이 읽는 글자라 지점 이름이 바뀌면 같이 바뀐다.
     */
    fun covers(storeKey: String, date: LocalDate, themeExternalId: String): Boolean =
        this.storeKey == storeKey && this.date == date &&
            (this.themeExternalId == null || this.themeExternalId == themeExternalId)
}
