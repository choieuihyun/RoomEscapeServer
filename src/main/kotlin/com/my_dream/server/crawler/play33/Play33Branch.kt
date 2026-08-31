package com.my_dream.server.crawler.play33

import com.my_dream.server.crawler.StoreRef

/**
 * 예약 페이지의 `branch` 파라미터 값. 지점 select 의 option value 에서 그대로 가져왔다.
 * value=6 은 "테스트" 지점이라 제외한다.
 *
 * [key] 는 우리 쪽 고유 식별자다. DB 의 매장 자연키이자 조회 API 의 `branch` 파라미터로 쓴다.
 */
enum class Play33Branch(val id: Int, val branchName: String, val key: String) {
    KONKUK(1, "건대점", "play33-konkuk"),
    HONGDAE(4, "홍대점", "play33-hongdae"),
    DAEJEON(5, "대전점", "play33-daejeon"),
    SUWON(7, "수원점", "play33-suwon"),
    ;

    /**
     * 이 지점을 긁을 때 실제로 두드리는 서버.
     *
     * **네 지점이 전부 같은 값이다.** 지점은 서버가 아니라 `?branch=N` 파라미터일 뿐이라,
     * 지점별로 동시에 요청하면 그 서버 한 대에 초당 4회가 간다. 수집 속도를 정하는 단위는
     * 지점이 아니라 여기다 (아키텍처 D13).
     */
    val host: String get() = HOST

    /** 공통 모양으로. 저장·조회는 매장 종류를 몰라도 되게 여기서 끊는다 */
    fun toStoreRef() = StoreRef(key = key, brand = BRAND, branchName = branchName)

    /**
     * 예약이 며칠치 열려 있나 (오늘 포함). **지점마다 다를 수 있는 값이다** —
     * 지구별이 대구만 2주, 홍대 두 곳이 1주였다. 매장 단위로 하나라고 믿지 않는다.
     *
     * **사이트가 스스로 밝힌다** — 예약 페이지의 `#reservation_range_day` 가 `7` 이다. 실측이 아니라 사이트 선언값이라 바뀌면 `warnIfRangeChanged` 가 알려 준다.
     */
    val openDays: Int get() = OPEN_DAYS

    companion object {
        /** 위 [openDays] 참고. 지점마다 갈리면 이 상수를 지우고 생성자 인자로 내린다 */
        const val OPEN_DAYS = 7

        const val BRAND = "플레이33"
        const val HOST = "play33.kr"
    }
}
