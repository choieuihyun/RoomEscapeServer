package com.my_dream.server.crawler.bitphobia

import com.my_dream.server.crawler.StoreRef

/**
 * `s_zizum` 파라미터 값. 2026-08-31 기준 9곳.
 *
 * ⚠️ **8번이 비어 있다.** `1,2,3,4,5,6,7,9,10` 이다. 없어진 지점으로 보인다 —
 * 지구별(1·2·4) · 포인트나인(1·5·6)과 같은 모양이다. **연속 번호로 순회하면 안 된다.**
 *
 * ⚠️ **1번이 기본값이다.** `s_zizum` 을 빼도 던전101 이 200 으로 정상 응답한다.
 */
enum class BitphobiaBranch(val id: Int, val branchName: String, val key: String) {
    DUNGEON101(1, "던전101", "bitphobia-dungeon101"),
    GANGNAM(2, "강남던전", "bitphobia-gangnam"),
    HONGDAE(3, "홍대던전", "bitphobia-hongdae"),
    GANGNAM2(4, "강남던전Ⅱ", "bitphobia-gangnam2"),
    HONGDAE3(5, "홍대던전Ⅲ", "bitphobia-hongdae3"),
    LUNA(6, "던전루나(강남)", "bitphobia-luna"),
    SEOMYEON(7, "서면던전(부산)", "bitphobia-seomyeon"),
    STELLA(9, "던전스텔라(강남)", "bitphobia-stella"),
    SEOMYEON_RED(10, "서면던전 레드(부산)", "bitphobia-seomyeon-red"),
    ;

    val host: String get() = HOST

    /**
     * **사이트가 안내 문구로 밝힌 값이다. 재서 얻은 값이 아니다.**
     *
     * > 2. 예약 오픈 시간 안내
     * > - 평일/주말 상관없이 해당 시간에 예약 오픈됩니다.
     * > - **매일 하루씩 예약 오픈하며 일주일 치의 예약만 가능합니다.**
     *
     * ⚠️ **재기만 했으면 6으로 잘못 적을 뻔했다.** 2026-08-31 12:20 에 재니
     * `+5` 는 회차가 있고 `+6` 은 0개였다 — 그날 오픈 시각이 아직 안 지났던 것뿐이다.
     * 6으로 박았으면 **매일 새로 열리는 날짜를 영영 안 보게 된다.**
     * 그 날짜가 갓 열려서 잡았다 놓는 일이 제일 많은, **취소표가 제일 잘 나는 자리**다.
     *
     * 오픈 **시각**은 이미지 안에 있어서 읽을 수 없다. 쫓아가지 않는다 —
     * 오픈 전에는 마지막 날짜의 회차가 0개로 오고, 오픈 뒤 바퀴에서 채워진다 (D22).
     */
    val openDays: Int get() = OPEN_DAYS

    fun toStoreRef() = StoreRef(key = key, brand = BRAND, branchName = branchName)

    companion object {
        /**
         * 푸터의 상호명이 `(주)비트포비아` 다. `og:site_name` 은 없고 `og:title` 은 빈 값이다.
         * `<title>` 은 `비트포비아 던전` 인데, `던전` 은 지점 이름들(던전101·강남던전…)에
         * 이미 들어가는 말이라 뺀다 — `방탈출 토끼굴` 에서 `방탈출` 을 뗀 것과 같다 (D19).
         */
        const val BRAND = "비트포비아"
        const val HOST = "xdungeon.net"

        /** 위 [openDays] 참고. 사이트 선언값이고, 지점마다 갈리면 생성자 인자로 내린다 */
        const val OPEN_DAYS = 7
    }
}
