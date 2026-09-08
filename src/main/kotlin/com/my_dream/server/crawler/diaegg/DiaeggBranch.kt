package com.my_dream.server.crawler.diaegg

import com.my_dream.server.crawler.StoreRef

/**
 * `s_zizum` 파라미터 값. 2026-09-04 실측.
 *
 * ⚠️ **테마 드롭다운만 보면 반드시 "1지점" 으로 적게 된다.** `go=rev.make` 를 그냥 열면
 * `<option>` 에 `[부산서면점]` 넷만 나온다. 지점은 그 위 **탭(`<a href="…s_zizum=2">`)** 에 있다.
 * 지구별·포인트나인에 이어 **세 번째로 같은 자리에서 틀릴 뻔했다**
 * ([방탈출 지점 요청_응답 정리.md](../../../../../../../../방탈출%20지점%20요청_응답%20정리.md) 확인 순서 ⓐ).
 *
 * ⚠️ **3번(육향찻집)은 여기 없다.** 탭에는 있지만 예약을 **네이버예약으로만** 받는다
 * (`m.booking.naver.com` · `robots.txt` 가 `Disallow : /`). 수집 대상이 아니다 —
 * 우회하지 않는다 (CLAUDE.md 수집 윤리). `s_zizum=3` 으로 `rev.make` 를 부르면
 * **200 이 오고 테마 이름까지 나오는데 회차가 0개**라, 넣어 두면 영원히 빈 지점이 된다.
 */
enum class DiaeggBranch(val id: Int, val branchName: String, val key: String) {
    DIAEGG(1, "다이아에그", "diaegg-seomyeon"),
    MYSTERYIN(2, "미스테리인", "diaegg-mysteryin"),
    ;

    val host: String get() = HOST

    fun toStoreRef() = StoreRef(key = key, brand = BRAND, branchName = branchName)

    /** 며칠치가 열려 있나. [leadDays] 만큼 뒤로 밀린 자리에서부터 센다 */
    val openDays: Int get() = OPEN_DAYS

    /**
     * **창이 오늘부터 시작하지 않는다.** 오늘~+3 은 회차가 0개고 +4 부터 열린다 (2026-09-04 실측).
     * 이 매장만 그렇다 — 다른 일곱 곳은 전부 0 이다.
     */
    val leadDays: Int get() = LEAD_DAYS

    companion object {
        /** 실측 2026-09-04 — 09-08~09-14 열림, 09-15 부터 닫힘 */
        const val OPEN_DAYS = 7

        /** 실측 2026-09-04 — 09-04~09-07 회차 0개, 09-08 부터 열림 */
        const val LEAD_DAYS = 4

        const val BRAND = "다이아에그"
        const val HOST = "diaegg.com"
    }
}
