package com.my_dream.server.crawler.zeroworld

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **브랜드는 둘, 호스트는 하나.** 그래서 어댑터를 나눠도 요청은 한 줄에 서야 한다 (D13).
 *
 * `StoreCollector` 가 `adapters.groupBy { it.host }` 로 묶으므로 구조상 보장되는데,
 * **그 전제가 깨지면 같은 서버에 동시에 두 줄이 나간다** — 수집 윤리를 정면으로 어기는 것이라
 * 전제 자체를 테스트로 박아 둔다.
 */
class ZeroworldAdapterTest {

    @Test
    fun `브랜드는 갈리고 호스트는 같다`() {
        val a = ZeroworldBranch.of("A")
        val b = ZeroworldBranch.of("B")

        assertEquals(3, a.size)
        assertEquals(1, b.size)
        assertTrue(a.all { it.brand == "제로월드" }, a.map { it.brand }.toString())
        assertTrue(b.all { it.brand == "제로월드 다이브" })
        // 여기가 같아야 StoreCollector 가 한 묶음으로 만든다
        assertTrue((a + b).all { it.host == ZeroworldBranch.HOST })
    }

    @Test
    fun `지점 번호가 브랜드 경계와 안 맞는다`() {
        // 1·4·5 가 A, 2 가 B 다. 번호로 브랜드를 짐작하면 틀린다 —
        // 3 은 아예 없고, 2 만 건너뛰어 B 다
        assertEquals(listOf(1, 4, 5), ZeroworldBranch.of("A").map { it.zizumNum })
        assertEquals(listOf(2), ZeroworldBranch.of("B").map { it.zizumNum })
    }

    @Test
    fun `네 지점 다 2주치를 연다`() {
        // 달력(act=calendar)이 밝힌 값이다. 재서 얻은 게 아니다
        assertTrue(ZeroworldBranch.entries.all { it.openDays == 15 })
    }
}
