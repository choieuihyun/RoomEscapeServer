package com.my_dream.server.crawler.rabbithole

import com.my_dream.server.crawler.StoreRef

/** `branch` 파라미터 값. 2026-08-27 기준 홍대 한 곳이다. */
enum class RabbitholeBranch(val id: Int, val branchName: String, val key: String) {
    HONGDAE(1, "홍대점", "rabbithole-hongdae"),
    ;

    val host: String get() = HOST

    fun toStoreRef() = StoreRef(key = key, brand = BRAND, branchName = branchName)

    companion object {
        const val BRAND = "래빗홀"
        const val HOST = "www.rabbitholeescape.co.kr"
    }
}
