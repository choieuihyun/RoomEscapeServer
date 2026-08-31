package com.my_dream.server.crawler.zeroworld

import com.my_dream.server.crawler.StoreRef

/**
 * `zizum_num` + `s_subj`. **한 사이트에 브랜드가 둘 들어 있다.**
 *
 * ```
 * s_subj=A  제로월드        1 김포본점 · 4 강남점 · 5 홍대점
 * s_subj=B  제로월드 다이브   2 건대점
 * ```
 *
 * ⚠️ **번호가 1·2·4·5 로 섞여 있고 브랜드 경계와도 안 맞는다.** `2` 만 B 다.
 * 지구별(1·2·4) · 포인트나인(1·5·6) · 비트포비아(8 없음)와 같은 모양 —
 * **연속 번호로 순회하면 안 된다.**
 *
 * 브랜드는 둘이지만 **호스트가 하나**라 요청은 한 줄로 선다 (D13).
 */
enum class ZeroworldBranch(
    val zizumNum: Int,
    val subject: String,
    val brand: String,
    val branchName: String,
    val key: String,
) {
    // 상수(ZEROWORLD 등)를 여기서 못 쓴다 — enum 항목이 companion 보다 먼저 만들어진다
    GIMPO(1, "A", "제로월드", "김포본점", "zeroworld-gimpo"),
    GANGNAM(4, "A", "제로월드", "강남점", "zeroworld-gangnam"),
    HONGDAE(5, "A", "제로월드", "홍대점", "zeroworld-hongdae"),
    DIVE_KONKUK(2, "B", "제로월드 다이브", "건대점", "zeroworld-dive-konkuk"),
    ;

    val host: String get() = HOST

    /**
     * **재서 얻은 값이 아니라 사이트의 달력이 밝힌 값이다.**
     *
     * ```
     * POST act=calendar&zizum_num=1&year=2026&month=9&s_subj=A
     *   → 2026-09-01 ~ 09-14 만 선택 가능, 10월은 전부 disable   (2026-08-31 확인)
     * ```
     *
     * 네 지점이 같다. **날짜를 하나씩 던져 보지 않았다** — 비트포비아에서 낮에 한 번 재고
     * `6일` 이라고 적을 뻔한 뒤로, **달력·안내문을 먼저 찾는다.** 하루 중 언제 물어도 같은 답이 온다.
     */
    val openDays: Int get() = OPEN_DAYS

    fun toStoreRef() = StoreRef(key = key, brand = brand, branchName = branchName)

    companion object {
        const val ZEROWORLD = "제로월드"
        const val ZEROWORLD_DIVE = "제로월드 다이브"
        const val HOST = "zeroworldkorea.com"

        /** 위 [openDays] 참고. 달력이 밝힌 값이다 */
        const val OPEN_DAYS = 15

        fun of(subject: String) = entries.filter { it.subject == subject }
    }
}
