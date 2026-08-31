package com.my_dream.server.crawler.keyescape

import com.my_dream.server.crawler.StoreRef

/**
 * `zizum_num` 파라미터 값. 예약 페이지의 지점 select 에서 그대로 가져왔다.
 * `movie_mood` 카테고리는 방탈출이 아니라 제외한다.
 *
 * [key] 는 우리 쪽 고유 식별자다 — DB 자연키이자 조회 API 의 `branch` 파라미터다.
 */
enum class KeyescapeBranch(val zizumNum: Int, val branchName: String, val key: String) {
    EVERLAND(26, "에버랜드", "keyescape-everland"),
    WHOSTHERE(23, "후즈데어", "keyescape-whosthere"),
    STATION(22, "STATION", "keyescape-station"),
    LOGIN1(19, "LOG_IN 1", "keyescape-login1"),
    LOGIN2(20, "LOG_IN 2", "keyescape-login2"),
    MEMORY(18, "메모리컴퍼니", "keyescape-memory"),
    UZULIKE(16, "우주라이크", "keyescape-uzulike"),
    THEOREUM(14, "더오름", "keyescape-theoreum"),
    GANGNAM(3, "강남점", "keyescape-gangnam"),
    HONGDAE(10, "홍대점", "keyescape-hongdae"),
    BUSAN(9, "부산점", "keyescape-busan"),
    ;

    /** 11지점이 전부 같은 서버다. 지점은 서버가 아니라 파라미터다 (아키텍처 D13) */
    val host: String get() = HOST

    fun toStoreRef() = StoreRef(key = key, brand = BRAND, branchName = branchName)

    /**
     * 예약이 며칠치 열려 있나 (오늘 포함). **지점마다 다를 수 있는 값이다** —
     * 지구별이 대구만 2주, 홍대 두 곳이 1주였다. 매장 단위로 하나라고 믿지 않는다.
     *
     * ⚠️ **미측정.** 지금 동작을 그대로 두려고 7 로 적었다. 재 본 적이 없으므로 **실측이 아니다** — 11지점이라 지점마다 다를 수도 있다.
     */
    val openDays: Int get() = OPEN_DAYS

    companion object {
        /** 위 [openDays] 참고. 지점마다 갈리면 이 상수를 지우고 생성자 인자로 내린다 */
        const val OPEN_DAYS = 7

        const val BRAND = "키이스케이프"
        const val HOST = "www.keyescape.com"
    }
}
