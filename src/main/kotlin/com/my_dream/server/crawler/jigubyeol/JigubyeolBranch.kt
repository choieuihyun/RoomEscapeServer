package com.my_dream.server.crawler.jigubyeol

import com.my_dream.server.crawler.StoreRef

/**
 * `branch` 파라미터 값. 2026-08-29 기준 3곳.
 *
 * ⚠️ **3번이 비어 있다.** `1, 2, 4` 다. 없어진 지점으로 보이는데,
 * 연속 번호로 가정하고 순회하면 빈 번호를 긁게 된다 — 그래서 목록을 손으로 적는다.
 * (비트포비아도 `s_zizum` 에 8번이 없다. 이 바닥에서 흔한 모양이다)
 */
enum class JigubyeolBranch(
    val id: Int,
    val branchName: String,
    val key: String,
    /**
     * ⚠️ **예약이 며칠치 열리는지가 지점마다 다르다.** 같은 브랜드인데 대구만 2주다 —
     * 2026-08-31 사용자 제보로 알고 실측했다 (302 = 아직 안 열림).
     *
     * ```
     * 대구         +13 200 · +14 200 · +15 302   2주 전 0시에 연다   → 15
     * 어드벤처      +6  200 · +7  302            1주 전 22시        →  7
     * 라스트시티    +6  200 · +8  302            1주 전 22시        →  7
     * ```
     *
     * **홍대 두 곳의 `+7` 은 22시가 지나면 열린다.** 창이 하루 중에 움직이는 것인데,
     * 우리는 `오늘..오늘+6` 만 보므로 **언제 돌든 안전하다.** 22시를 쫓아가지 않는다 —
     * 하루에 두 가지로 행동하는 코드는 새벽에만 나는 버그를 만든다.
     */
    val openDays: Int,
) {
    DAEGU(1, "대구점", "jigubyeol-daegu", openDays = 15),
    HONGDAE_ADVENTURE(2, "홍대어드벤처점", "jigubyeol-hongdae-adventure", openDays = 7),
    HONGDAE_LASTCITY(4, "홍대라스트시티점", "jigubyeol-hongdae-lastcity", openDays = 7),
    ;

    val host: String get() = HOST

    fun toStoreRef() = StoreRef(key = key, brand = BRAND, branchName = branchName)

    companion object {
        const val BRAND = "지구별"

        /**
         * **punycode 로 적는다.** 실제 도메인은 한글 `지구별.com` 이지만,
         * 자바 HTTP 클라이언트는 한글 호스트를 알아서 변환해 주지 않는다.
         * 여기에 한글을 적으면 이름 해석부터 실패한다.
         */
        const val HOST = "www.xn--2e0b040a4xj.com"
    }
}
