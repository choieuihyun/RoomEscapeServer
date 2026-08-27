package com.my_dream.server.crawler.play33

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

    companion object {
        const val BRAND = "플레이33"
        const val HOST = "play33.kr"
    }
}
