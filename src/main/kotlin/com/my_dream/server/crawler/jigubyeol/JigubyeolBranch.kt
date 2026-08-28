package com.my_dream.server.crawler.jigubyeol

import com.my_dream.server.crawler.StoreRef

/**
 * `branch` 파라미터 값. 2026-08-29 기준 3곳.
 *
 * ⚠️ **3번이 비어 있다.** `1, 2, 4` 다. 없어진 지점으로 보이는데,
 * 연속 번호로 가정하고 순회하면 빈 번호를 긁게 된다 — 그래서 목록을 손으로 적는다.
 * (비트포비아도 `s_zizum` 에 8번이 없다. 이 바닥에서 흔한 모양이다)
 */
enum class JigubyeolBranch(val id: Int, val branchName: String, val key: String) {
    DAEGU(1, "대구점", "jigubyeol-daegu"),
    HONGDAE_ADVENTURE(2, "홍대어드벤처점", "jigubyeol-hongdae-adventure"),
    HONGDAE_LASTCITY(4, "홍대라스트시티점", "jigubyeol-hongdae-lastcity"),
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
