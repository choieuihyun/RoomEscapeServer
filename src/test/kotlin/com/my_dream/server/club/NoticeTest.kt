package com.my_dream.server.club

import com.my_dream.server.domain.ClubMember
import com.my_dream.server.domain.ClubMemberRepository
import com.my_dream.server.domain.ClubNoticeRepository
import com.my_dream.server.domain.MemberRole
import com.my_dream.server.domain.MemberStatus
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 홈 공지 (§4.14 · D46). 문(401·403)부터 알림까지 진짜로 커밋하며 본다 — 알림은 커밋 뒤에 나가야 해서
 * 테스트 트랜잭션 안에서는 확인이 안 된다 ([ClubAlertsCommitTest] 와 같다)
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(NoticeTest.Config::class)
class NoticeTest @Autowired constructor(
    private val mvc: MockMvc,
    private val service: NoticeService,
    private val notifier: RecordingNotifier,
    private val members: ClubMemberRepository,
    private val notices: ClubNoticeRepository,
) {

    @TestConfiguration
    class Config {
        @Bean
        @Primary
        fun recordingNotifier() = RecordingNotifier()
    }

    @BeforeEach
    fun setUp() {
        notices.deleteAll()
        members.deleteAll()
        members.save(ClubMember("방장", "열쇠", MemberRole.ADMIN, MemberStatus.APPROVED, Instant.now()))
        members.save(ClubMember("나", "닉나", MemberRole.MEMBER, MemberStatus.APPROVED, Instant.now()))
        members.save(ClubMember("너", "닉너", MemberRole.MEMBER, MemberStatus.APPROVED, Instant.now()))
        members.save(ClubMember("대기", "대기", MemberRole.MEMBER, MemberStatus.PENDING, Instant.now()))
        notifier.pushes.clear()
    }

    private fun as_(uid: String) = jwt().jwt { it.subject(uid) }

    private fun putJson(uid: String, body: String) =
        mvc.perform(put("/api/club/notice").with(as_(uid)).contentType(MediaType.APPLICATION_JSON).content(body))

    private fun awaitPushes(n: Int) {
        repeat(50) { if (notifier.pushes.size >= n) return; Thread.sleep(50) }
    }

    @Test
    fun `없으면 본문이 null, 쓰면 보이고, 고칠 때마다 id 가 커지고, 내리면 다시 null`() {
        mvc.perform(get("/api/club/notice")).andExpect(status().isUnauthorized)
        // 빈 본문이 아니라 null — 화면의 res.json() 이 빈 본문에서 터진다
        mvc.perform(get("/api/club/notice").with(as_("나"))).andExpect(status().isOk).andExpect(content().string("null"))

        putJson("방장", """{"text":"  이번 주 정모는\n토요일이에요  "}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.text").value("이번 주 정모는\n토요일이에요"))
            .andExpect(jsonPath("$.updatedBy.uid").value("방장"))
            .andExpect(jsonPath("$.updatedBy.nickname").value("열쇠"))
            .andExpect(jsonPath("$.updatedAt").exists())
        val first = notices.findTopByOrderByIdDesc()!!.id!!
        mvc.perform(get("/api/club/notice").with(as_("나"))).andExpect(jsonPath("$.id").value(first))

        putJson("방장", """{"text":"고친 공지"}""").andExpect(status().isOk)
        val second = notices.findTopByOrderByIdDesc()!!.id!!
        assertTrue(second > first, "고친 공지는 새 id — 앱이 닫은 공지를 id 로 기억한다")
        assertEquals(1, notices.count(), "한 번에 하나만")

        mvc.perform(delete("/api/club/notice").with(as_("방장"))).andExpect(status().isOk).andExpect(jsonPath("$.deleted").value(true))
        mvc.perform(delete("/api/club/notice").with(as_("방장"))).andExpect(status().isOk).andExpect(jsonPath("$.deleted").value(true))
        mvc.perform(get("/api/club/notice").with(as_("나"))).andExpect(content().string("null"))
        assertEquals(0, notifier.pushes.size, "notify 가 없으면 알림도 없다")
    }

    @Test
    fun `보기는 승인 멤버, 쓰기·내리기는 운영진, 틀린 글은 BAD_NOTICE`() {
        mvc.perform(get("/api/club/notice").with(as_("대기"))).andExpect(status().isForbidden).andExpect(jsonPath("$.error").value("NOT_CLUB_MEMBER"))
        putJson("나", """{"text":"몰래"}""").andExpect(status().isForbidden).andExpect(jsonPath("$.error").value("NOT_ADMIN"))
        mvc.perform(delete("/api/club/notice").with(as_("나"))).andExpect(status().isForbidden).andExpect(jsonPath("$.error").value("NOT_ADMIN"))

        for (bad in listOf("""{"text":"   "}""", """{}""", """{"text":"${"가".repeat(501)}"}""", """{"text":"${(1..11).joinToString("\\n")}"}""", "{깨짐")) {
            putJson("방장", bad)
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.error").value("BAD_NOTICE"))
                .andExpect(jsonPath("$.message").value("공지는 1~500자, 10줄까지 적을 수 있어요."))
        }
        // 경계: 500자 · 10줄은 된다. 이모지 500자는 Postgres 에서 본다(MigrationValidationTest) — 이 테스트의 H2 는
        // VARCHAR 를 UTF-16 으로 세서 이모지를 두 자로 친다. 운영은 Postgres 라 코드 포인트로 센다
        putJson("방장", """{"text":"${"가".repeat(500)}"}""").andExpect(status().isOk)
        putJson("방장", """{"text":"${(1..10).joinToString("\\n")}"}""").andExpect(status().isOk)
    }

    @Test
    fun `notify 면 커밋 뒤 승인 멤버에게(쓴 사람 빼고) 한 번, 같은 운영진은 1분에 한 번`() {
        putJson("방장", """{"text":"${"가".repeat(61)}\n둘째 줄","notify":true}""").andExpect(status().isOk)
        awaitPushes(2)
        assertEquals(setOf("나", "너"), notifier.pushes.map { it.userId }.toSet(), "대기 중인 사람과 쓴 사람은 안 받는다")
        val p = notifier.pushes.first()
        assertEquals("새 공지가 있어요", p.title)
        assertEquals("가".repeat(60) + "…", p.body)
        assertEquals("#notice", p.linkFragment)

        // 1분 안에 또 — 저장은 되고 알림만 건너뛴다
        notifier.pushes.clear()
        putJson("방장", """{"text":"또 고침","notify":true}""").andExpect(status().isOk).andExpect(jsonPath("$.text").value("또 고침"))
        Thread.sleep(300)
        assertEquals(0, notifier.pushes.size)
    }

    @Test
    fun `글 규칙과 알림 본문`() {
        assertEquals("짧은 줄", NoticeService.preview("짧은 줄  \n다음"))
        assertEquals("🙂".repeat(60) + "…", NoticeService.preview("🙂".repeat(61)), "코드 포인트로 자른다 — 이모지가 반쪽 나지 않게")
        assertEquals("a\r\nb", NoticeService.clean(" a\r\nb "))
        assertFailsWith<ClubError> { NoticeService.clean((1..11).joinToString("\r\n")) }
        assertFailsWith<ClubError> { NoticeService.clean(null) }
        // 1분 제한은 운영진마다 따로, 1분이 지나면 다시
        val t = Instant.parse("2030-01-01T00:00:00Z")
        service.put("방장", NoticeInput("a", notify = true), t)
        awaitPushes(2)
        Thread.sleep(200)
        notifier.pushes.clear()
        members.save(ClubMember("부방장", "부", MemberRole.ADMIN, MemberStatus.APPROVED, Instant.now()))
        service.put("부방장", NoticeInput("b", notify = true), t)
        service.put("방장", NoticeInput("c", notify = true), t.plusSeconds(60))
        awaitPushes(6)
        assertEquals(6, notifier.pushes.size, "부방장 → 3명 · 방장(1분 뒤) → 3명 (대기 중인 사람 빼고)")
    }
}
