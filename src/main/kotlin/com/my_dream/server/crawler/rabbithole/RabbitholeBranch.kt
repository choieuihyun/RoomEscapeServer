package com.my_dream.server.crawler.rabbithole

import com.my_dream.server.crawler.StoreRef

/** `branch` 파라미터 값. 2026-08-27 기준 홍대 한 곳이다. */
enum class RabbitholeBranch(val id: Int, val branchName: String, val key: String) {
    HONGDAE(1, "홍대점", "rabbithole-hongdae"),
    ;

    val host: String get() = HOST

    fun toStoreRef() = StoreRef(key = key, brand = BRAND, branchName = branchName)

    /**
     * 예약이 며칠치 열려 있나 (오늘 포함). **지점마다 다를 수 있는 값이다** —
     * 지구별이 대구만 2주, 홍대 두 곳이 1주였다. 매장 단위로 하나라고 믿지 않는다.
     *
     * ⚠️ **미측정.** 지금 동작을 그대로 두려고 7 로 적었다. 재 본 적이 없으므로 **실측이 아니다** — 이 매장의 창을 넓힐 일이 생기면 그때 재고 이 주석을 고친다.
     */
    val openDays: Int get() = OPEN_DAYS

    companion object {
        /** 위 [openDays] 참고. 지점마다 갈리면 이 상수를 지우고 생성자 인자로 내린다 */
        const val OPEN_DAYS = 7

        /**
         * **화면에 보이는 이름과 내부 이름이 일부러 다르다. 맞추려고 하지 말 것.**
         *
         * `BRAND` 는 **매장이 스스로를 부르는 이름**이라 사용자에게 그대로 나간다.
         * 나머지(`key` · [HOST] · 패키지명 · 픽스처 파일명)는 **도메인**을 따른다.
         *
         * 처음에 `BRAND` 를 `"래빗홀"` 로 적었던 것은 도메인을 그대로 옮겨 적은 것이고,
         * **오기였다.** 사이트는 `og:site_name` 부터 로고 alt · 상단 메뉴까지
         * 전부 `방탈출 토끼굴` 이라고 쓴다 (픽스처 `rabbithole-hongdae-2026-08-29.html` 28행).
         * 2026-08-31 에 `토끼굴` 로 고쳤다. `방탈출` 을 뗀 것은 다른 매장도 다 뗀 채로
         * 저장하기 때문이다 — `플레이33` · `키이스케이프` · `지구별`.
         *
         * ⚠️ **[key] 는 같이 바꾸지 않는다.** 자연키라서 값이 바뀌면
         * `ScheduleSyncService.findOrCreateStore` 가 기존 행을 못 찾고 매장을 하나 더 만든다.
         * 그러면 옛 행에 달린 테마·회차가 통째로 고아가 되고 **그 지점의 전이 감지가 조용히 멈춘다.**
         * 표시 이름만 바꾸는 일에 서비스의 본체를 걸 이유가 없다.
         */
        const val BRAND = "토끼굴"
        const val HOST = "www.rabbitholeescape.co.kr"
    }
}
