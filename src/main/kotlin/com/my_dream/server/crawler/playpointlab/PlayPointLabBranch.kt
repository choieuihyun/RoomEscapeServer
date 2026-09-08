package com.my_dream.server.crawler.playpointlab

import com.my_dream.server.crawler.StoreRef

/**
 * `store` 파라미터 값. 2026-09-04 실측.
 *
 * ⚠️ **`playpointlab.com` 은 한 매장이 아니라 디렉터리다.** `/office` 는 **3개 브랜드
 * 17지점**(플레이포인트랩 7 · 마스터키 8 · 마스터키노바 1)을 모아 놓은 목록이고,
 * 예약 데이터는 실제로 `master-key.co.kr` 것이다(포스터 URL 이 거기를 가리킨다).
 *
 * **이번에 넣은 것은 부산 3곳뿐이다** — 사용자가 그렇게 지정했다. 나머지 14곳은
 * [방탈출 지점 요청_응답 정리.md](../../../../../../../../방탈출%20지점%20요청_응답%20정리.md)
 * 에 `bid` 까지 적어 뒀다. **안 하기로 정한 게 아니라 이번 범위가 아닐 뿐이라**,
 * 늘릴 때 디렉터리를 다시 파헤칠 필요는 없다.
 *
 * ⚠️ **`store` 는 `bid` 와 같은 값이다.** 상세 페이지의 인라인 스크립트가
 * `list_ajax(selected_date, "44", …)` 로 자기 `bid` 를 그대로 넘긴다 —
 * **세 지점 모두에서 확인했다.** 하나만 보고 일반화하지 않았다.
 */
enum class PlayPointLabBranch(
    val id: Int,
    val branchName: String,
    val key: String,
    /**
     * 이 지점에 있는 `data-room-id` 전부 (2026-09-04 실측).
     *
     * **응답에 지점을 밝히는 값이 하나도 없어서** 이게 유일한 대조 수단이다 —
     * 조각 HTML 이라 `store` 도 지점명도 안 실려 온다. 세 지점의 room-id 가
     * 하나도 안 겹치므로, 받은 room-id 가 이 집합 밖이면 `store` 가 무시된 것이다.
     *
     * 테마가 늘거나 줄면 여기도 고쳐야 한다. **고칠 때까지 수집이 끊긴다** —
     * 조용히 다른 지점 데이터를 먹는 것보다 낫다고 보고 그렇게 뒀다.
     */
    val roomIds: Set<String>,
) {
    SEOMYEON_ORIGIN(44, "부산서면오리진점", "playpointlab-seomyeon-origin", setOf("220", "222", "224", "234")),
    TANTAN_STREET(43, "서면탄탄스트리트점", "playpointlab-tantan", setOf("217", "218", "219")),
    HAEUNDAE(40, "해운대 블루오션스테이션", "playpointlab-haeundae", setOf("201", "202", "203", "204", "206", "223")),
    ;

    val host: String get() = HOST

    fun toStoreRef() = StoreRef(key = key, brand = BRAND, branchName = branchName)

    val openDays: Int get() = OPEN_DAYS

    companion object {
        /**
         * **화면이 여는 만큼만 본다.** 날짜 버튼을 만드는 스크립트가 `for (i = 0; i < 8; i++)`
         * 라 사용자에게는 오늘~+7 만 보인다.
         *
         * 서버는 그보다 더 준다 — `date=오늘+8` 로 직접 POST 하면 200 에 회차도 온다
         * (2026-09-12 실측, 34슬롯). **그래도 안 긁는다.** 사용자가 예약할 수 없는 날짜의
         * 취소표를 알려 주면 예약 버튼이 없는 화면으로 보내는 꼴이라 헛알림이다.
         */
        const val OPEN_DAYS = 8

        const val BRAND = "플레이포인트랩"
        const val HOST = "playpointlab.com"
    }
}
