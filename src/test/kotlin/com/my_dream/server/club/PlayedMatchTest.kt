package com.my_dream.server.club

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** "같은 테마인가" — 네 군데가 같이 쓰는 판정 하나 (D33) */
class PlayedMatchTest {

    private fun k(name: String) = PlayedMatch.nameKey(name)

    @Test
    fun `이름은 공백·문장부호·대소문자·전각을 지우고 비교한다`() {
        assertEquals(k("비밀의 화원"), k(" 비밀의화원!"))
        assertEquals(k("ABC 룸"), k("ａｂｃ룸"))
        assertEquals("", k("!!! 🙂"), "글자·숫자가 없으면 빈다")
    }

    @Test
    fun `키가 둘 다 있으면 키로만, 한쪽이라도 없으면 이름으로`() {
        assertTrue(PlayedMatch.same("p:1", k("가"), "p:1", k("다른 이름")), "이름이 바뀌어도 같은 키면 같은 테마")
        assertFalse(PlayedMatch.same("p:1", k("목격자"), "q:7", k("목격자")), "다른 지점의 같은 이름 — 키가 다르면 다르다")
        assertTrue(PlayedMatch.same(null, k("목격자"), "q:7", k("목격자")))
        assertTrue(PlayedMatch.same(null, k("목격자"), null, k("목 격 자")))
    }

    @Test
    fun `이행적이지 않다 — 그래서 UNIQUE 로 못 건다`() {
        // 키 없는 "목격자" 는 p:1 과도, q:7 과도 같다. 그런데 p:1 과 q:7 은 다르다
        val none = null to k("목격자")
        assertTrue(PlayedMatch.same(none.first, none.second, "p:1", k("목격자")))
        assertTrue(PlayedMatch.same(none.first, none.second, "q:7", k("목격자")))
        assertFalse(PlayedMatch.same("p:1", k("목격자"), "q:7", k("목격자")))
    }

    @Test
    fun `빈 이름은 이름으로는 아무것과도 같지 않다`() {
        assertFalse(PlayedMatch.same(null, "", null, ""))
        assertTrue(PlayedMatch.same("p:1", "", "p:1", ""), "키가 같으면 이름과 상관없다")
    }
}
