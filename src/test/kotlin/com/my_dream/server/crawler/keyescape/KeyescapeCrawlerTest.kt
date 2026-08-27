package com.my_dream.server.crawler.keyescape

import com.my_dream.server.crawler.HostRateLimiter
import org.junit.jupiter.api.Test
import org.springframework.core.ParameterizedTypeReference
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 실제로 받아둔 홍대점 응답으로 검증한다.
 * 사이트가 응답 모양을 바꾸면 이 테스트가 먼저 깨져서 알려준다 (아키텍처 D5).
 */
class KeyescapeCrawlerTest {

    private val mapper = JsonMapper.builder().addModule(kotlinModule()).build()

    private fun <T> fixture(name: String, type: ParameterizedTypeReference<KeyescapeEnvelope<T>>): T {
        val json = requireNotNull(javaClass.getResource("/$name")).readText()
        val env: KeyescapeEnvelope<T> = mapper.readValue(json, mapper.constructType(type.type))
        assertTrue(env.status, "픽스처가 거절 응답이다")
        return requireNotNull(env.data)
    }

    private val themeRows: List<KeyescapeThemeRow> = fixture(
        "keyescape-themes-hongdae.json",
        object : ParameterizedTypeReference<KeyescapeEnvelope<List<KeyescapeThemeRow>>>() {},
    )
    private val detail: KeyescapeThemeDetail = fixture(
        "keyescape-theme-detail-21.json",
        object : ParameterizedTypeReference<KeyescapeEnvelope<KeyescapeThemeDetail>>() {},
    )
    private val slotRows: List<KeyescapeSlotRow> = fixture(
        "keyescape-slots-hongdae-41-2026-08-28.json",
        object : ParameterizedTypeReference<KeyescapeEnvelope<List<KeyescapeSlotRow>>>() {},
    )

    /** 픽스처를 그대로 돌려주는 가짜 클라이언트. 응답 값만 바꿔 끼울 수 있다 */
    private inner class FakeClient(
        private val slots: List<KeyescapeSlotRow> = slotRows,
    ) : KeyescapeClient(HostRateLimiter(0)) {
        override fun themeList(branch: KeyescapeBranch) = themeRows
        override fun themeDetail(infoNum: Int) = detail
        override fun slots(branch: KeyescapeBranch, themeNum: Int, date: LocalDate) = slots
    }

    private val date = LocalDate.of(2026, 8, 28)
    private val bbirit = KeyescapeTheme(
        infoNum = 21, themeNum = 41, name = "삐릿-뽀",
        genre = "어드벤처", difficulty = 4.0, runningMinutes = 75, posterUrl = null,
    )

    @Test
    fun `snake_case 필드를 읽는다`() {
        // info_num · theme_num · info_name · play_time · image_url — 하나라도 어긋나면 null 이 된다
        assertEquals(listOf(21, 22, 23), themeRows.map { it.infoNum })
        assertEquals(listOf(41, 45, 43), themeRows.map { it.themeNum })
        assertEquals("삐릿-뽀", themeRows.first().infoName)
        assertEquals("75분", detail.playTime)
        assertTrue(detail.imageUrl!!.startsWith("https://"))
    }

    @Test
    fun `info_num 과 theme_num 은 다른 값이다`() {
        // 이게 같았다면 ID 함정을 표본이 못 담는다. 삐릿-뽀는 21 과 41 이다
        val row = themeRows.single { it.infoName == "삐릿-뽀" }
        assertTrue(row.infoNum != row.themeNum, "픽스처가 함정을 못 담고 있다")
    }

    @Test
    fun `enable 로 예약 가능을 판단한다`() {
        val day = KeyescapeCrawler(FakeClient()).fetch(KeyescapeBranch.HONGDAE, bbirit, date)
        val slots = day.themes.single().slots

        assertEquals(8, slots.size)
        assertEquals(3, slots.count { it.available }, "가능")
        assertEquals(5, slots.count { !it.available }, "매진")
        assertEquals(LocalTime.of(10, 0), slots.first().time, "hh·mm 이 \"10\"·\"00\" 처럼 0 이 붙어서 온다")
    }

    @Test
    fun `모르는 enable 값은 불가로 본다`() {
        // 잘못 "가능" 으로 읽으면 감시 걸어둔 사람 전원에게 헛알림이 나간다.
        // 틀려도 안전한 쪽으로 틀려야 한다
        val odd = slotRows.map { it.copy(enable = "???") }
        val day = KeyescapeCrawler(FakeClient(odd)).fetch(KeyescapeBranch.HONGDAE, bbirit, date)

        assertTrue(day.themes.single().slots.none { it.available })
    }

    @Test
    fun `다른 테마의 응답이 오면 예외로 끊는다`() {
        // ID 공간을 섞어 보내면 에러가 아니라 엉뚱한 테마가 그럴듯하게 온다.
        // 그대로 저장하면 남의 시간표가 이 테마 자리에 덮인다
        val wrong = slotRows.map { it.copy(themeNum = 999) }

        assertFailsWith<KeyescapeCrawlException> {
            KeyescapeCrawler(FakeClient(wrong)).fetch(KeyescapeBranch.HONGDAE, bbirit, date)
        }
    }

    @Test
    fun `회차가 없는 테마도 예외가 아니다`() {
        // 에버랜드는 7일 내내 0회차였다. 없는 것과 못 받은 것은 다르고,
        // 못 받은 쪽은 클라이언트가 status=false 로 이미 끊는다
        val day = KeyescapeCrawler(FakeClient(emptyList())).fetch(KeyescapeBranch.HONGDAE, bbirit, date)

        assertTrue(day.themes.single().slots.isEmpty())
    }

    @Test
    fun `테마 메타데이터를 옮겨 담는다`() {
        val day = KeyescapeCrawler(FakeClient()).fetch(KeyescapeBranch.HONGDAE, bbirit, date)
        val theme = day.themes.single()

        assertEquals("삐릿-뽀", theme.themeName)
        assertEquals("41", theme.externalId, "이름이 바뀌어도 이어붙일 키")
        assertEquals("어드벤처", theme.genre)
        assertEquals(75, theme.runningMinutes)
        assertEquals(4.0, theme.difficulty)
        // 사이트가 인원·공포도를 주지 않는다. 없는 걸 0 으로 채우지 않는다
        assertNull(theme.capacity)
        assertNull(theme.horrorLevel)
    }

    @Test
    fun `지점이 공통 모양으로 바뀐다`() {
        val day = KeyescapeCrawler(FakeClient()).fetch(KeyescapeBranch.HONGDAE, bbirit, date)

        assertEquals("keyescape-hongdae", day.store.key)
        assertEquals("키이스케이프", day.store.brand)
        assertEquals("홍대점", day.store.branchName)
    }
}

/** "안 여는 날짜" 를 실패와 구분하는가. 실제 거절 응답을 물고 있다. */
class KeyescapeNotOpenTest {

    private val mapper = JsonMapper.builder().addModule(kotlinModule()).build()

    private val rejected: KeyescapeEnvelope<List<KeyescapeSlotRow>> = mapper.readValue(
        requireNotNull(javaClass.getResource("/keyescape-slots-notopen.json")).readText(),
        mapper.constructType(
            object : ParameterizedTypeReference<KeyescapeEnvelope<List<KeyescapeSlotRow>>>() {}.type,
        ),
    )

    @Test
    fun `에버랜드는 안 여는 날이라고 답한다`() {
        assertTrue(!rejected.status, "거절 응답이어야 한다")
        assertTrue(
            KeyescapeClient.isNotOpen(rejected.msg),
            "실제 응답 문구를 못 알아본다: ${rejected.msg}",
        )
    }

    @Test
    fun `시간이 없다는 거절도 알아본다`() {
        // 날짜 거절과 문구가 다르다. 모르는 거절을 예외로 끊어 뒀기 때문에 드러났다
        val json = requireNotNull(javaClass.getResource("/keyescape-slots-notime.json")).readText()
        val env: KeyescapeEnvelope<List<KeyescapeSlotRow>> = mapper.readValue(
            json,
            mapper.constructType(
                object : ParameterizedTypeReference<KeyescapeEnvelope<List<KeyescapeSlotRow>>>() {}.type,
            ),
        )
        assertTrue(KeyescapeClient.isNotOpen(env.msg), "실제 응답 문구를 못 알아본다: ${env.msg}")
    }

    @Test
    fun `모르는 거절은 실패로 본다`() {
        // 빈 리스트로 뭉개면 저장 단계가 멀쩡한 데이터를 지운다.
        // 문구가 바뀌어도 조용히 비우는 것보다 시끄럽게 깨지는 쪽이 낫다
        assertTrue(!KeyescapeClient.isNotOpen("서버 점검 중입니다"))
        assertTrue(!KeyescapeClient.isNotOpen(null))
    }
}
