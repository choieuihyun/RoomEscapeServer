package com.my_dream.server.crawler.pointnine

import com.my_dream.server.crawler.StoreRef

/**
 * `s_zizum` 파라미터 값. 2026-08-31 실측으로 3지점이다.
 *
 * ⚠️ **번호가 1·5·6 으로 띄엄띄엄하다.** 지구별(1·2·4)과 같은 모양이다 —
 * 없앤 지점의 번호가 비는 것으로 보인다. **`1..N` 으로 훑으면 안 된다.**
 * `<option>` 에 실제로 적힌 값만 쓴다.
 *
 * ⚠️ **1번이 기본값이다.** `s_zizum` 을 빼고 요청해도 강남점이 200 으로 정상 응답한다.
 * 8/28 조사가 그래서 "1지점" 이라고 잘못 적었다 — 기본값과 비교하면 아무것도 확인이 안 된다.
 */
enum class PointNineBranch(val id: Int, val branchName: String, val key: String) {
    GANGNAM(1, "강남점", "pointnine-gangnam"),
    KONKUK(5, "건대점", "pointnine-konkuk"),
    HONGDAE(6, "홍대점", "pointnine-hongdae"),
    ;

    val host: String get() = HOST

    fun toStoreRef() = StoreRef(key = key, brand = BRAND, branchName = branchName)

    /**
     * 예약이 며칠치 열려 있나 (오늘 포함). **지점마다 다를 수 있는 값이다** —
     * 지구별이 대구만 2주, 홍대 두 곳이 1주였다. 매장 단위로 하나라고 믿지 않는다.
     *
     * **실측** (2026-08-31) — `+6` 은 회차가 오고 `+7` 부터 200 인데 회차가 0개다. 지점 셋 다 같다.
     */
    val openDays: Int get() = OPEN_DAYS

    companion object {
        /** 위 [openDays] 참고. 지점마다 갈리면 이 상수를 지우고 생성자 인자로 내린다 */
        const val OPEN_DAYS = 7

        const val BRAND = "포인트나인"
        const val HOST = "point-nine.com"
    }
}
