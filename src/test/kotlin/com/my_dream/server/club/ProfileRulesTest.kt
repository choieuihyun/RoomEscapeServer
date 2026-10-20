package com.my_dream.server.club

import org.junit.jupiter.api.Test
import java.math.BigInteger
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * 프로필 칸 검사 (§4.11 · D35). 화면이 같은 검사를 하더라도 서버가 다시 막아야 한다 —
 * 여기서 놓친 값은 다른 멤버의 화면에서 터진다(모르는 MBTI·장르를 그릴 수 없다).
 */
class ProfileRulesTest {

    private val today = LocalDate.of(2026, 10, 20)

    private fun input(
        realName: String? = "김철수",
        birthDate: String? = "1997-06-28",
        mbti: String? = "INTJ",
        roomCount: Any? = 120,
        genres: List<Any?>? = listOf("공포", "추리"),
        lifeTheme: LifeThemeInput? = LifeThemeInput("비밀의 화원", "강남점", "keyescape-3:7"),
    ) = ProfileInput(realName, birthDate, mbti, roomCount, genres, lifeTheme)

    private fun rejects(p: ProfileInput) {
        val e = assertFailsWith<ClubError> { ProfileRules.clean(p, today) }
        assertEquals("BAD_PROFILE", e.code)
    }

    @Test
    fun `맞는 값은 다듬어서 받는다`() {
        val p = ProfileRules.clean(input(realName = " 김철수 ", genres = listOf("추리", "공포", "SF")), today)
        assertEquals("김철수", p.realName)
        assertEquals(LocalDate.of(1997, 6, 28), p.birthDate)
        assertEquals(listOf("추리", "공포", "SF"), p.genres, "보낸 순서 그대로")
        assertEquals(120, p.roomCount)
    }

    @Test
    fun `이름은 코드 포인트로 1~20자`() {
        rejects(input(realName = "   "))
        rejects(input(realName = null))
        rejects(input(realName = "가".repeat(21)))
        // 이모지 하나를 하나로 센다 — String.length 로 세면 10개에서 걸린다. DB 쪽 셈은 MigrationValidationTest
        assertEquals(20, ProfileRules.clean(input(realName = "🙂".repeat(20)), today).realName.codePointCount(0, 40))
    }

    @Test
    fun `생일 — 없는 날짜, 1940년 전, 오늘 뒤, 점 찍은 형식은 안 된다`() {
        rejects(input(birthDate = "1999-02-29"))   // 평년
        rejects(input(birthDate = "2026-02-30"))
        rejects(input(birthDate = "1939-12-31"))
        rejects(input(birthDate = "2026-10-21"))
        rejects(input(birthDate = "1997.06.28"))   // 화면이 YYYY-MM-DD 로 바꿔 보낸다
        rejects(input(birthDate = null))
        assertEquals(LocalDate.of(2000, 2, 29), ProfileRules.clean(input(birthDate = "2000-02-29"), today).birthDate)
        assertEquals(today, ProfileRules.clean(input(birthDate = "2026-10-20"), today).birthDate)
        assertEquals(LocalDate.of(1940, 1, 1), ProfileRules.clean(input(birthDate = "1940-01-01"), today).birthDate)
    }

    @Test
    fun `MBTI 는 16가지와 UNKNOWN 만, 대문자로`() {
        rejects(input(mbti = "ABCD"))
        rejects(input(mbti = "intj"))
        rejects(input(mbti = null))
        assertEquals("UNKNOWN", ProfileRules.clean(input(mbti = "UNKNOWN"), today).mbti)
        assertEquals(17, ProfileRules.MBTI.size)
    }

    @Test
    fun `방 수는 0~9999 정수만 — 잭슨이 조용히 바꿔 주는 소수와 문자열도 거절`() {
        rejects(input(roomCount = 1.5))
        rejects(input(roomCount = "12"))
        rejects(input(roomCount = -1))
        rejects(input(roomCount = 10000))
        rejects(input(roomCount = null))
        rejects(input(roomCount = BigInteger("99999999999999999999")))
        assertEquals(0, ProfileRules.clean(input(roomCount = 0), today).roomCount)
        assertEquals(9999, ProfileRules.clean(input(roomCount = 9999L), today).roomCount)
    }

    @Test
    fun `장르는 목록 안에서 1~5개, 겹침 없이`() {
        rejects(input(genres = emptyList()))
        rejects(input(genres = null))
        rejects(input(genres = listOf("공포", "추리", "감성", "SF", "코믹", "액션")))
        rejects(input(genres = listOf("공포", "공포")))
        rejects(input(genres = listOf("호러")))
        rejects(input(genres = listOf(3)))
        rejects(input(genres = listOf(null)))
        assertEquals(5, ProfileRules.clean(input(genres = listOf("공포", "추리", "감성", "SF", "코믹")), today).genres.size)
        assertEquals(13, ProfileRules.GENRES.size)
    }

    @Test
    fun `인생 테마 — 이름은 정리해도 남아야 하고, 지점·키는 비우면 없음`() {
        rejects(input(lifeTheme = null))
        rejects(input(lifeTheme = LifeThemeInput(null, null, null)))
        rejects(input(lifeTheme = LifeThemeInput("!!!", null, null)))
        rejects(input(lifeTheme = LifeThemeInput("가".repeat(41), null, null)))
        rejects(input(lifeTheme = LifeThemeInput("테마", "가".repeat(41), null)))
        rejects(input(lifeTheme = LifeThemeInput("테마", null, "k".repeat(81))))
        val p = ProfileRules.clean(input(lifeTheme = LifeThemeInput(" 테마 ", "  ", "")), today)
        assertEquals("테마", p.lifeThemeName)
        assertNull(p.lifeThemeBranch)
        assertNull(p.lifeThemeKey)
    }
}
