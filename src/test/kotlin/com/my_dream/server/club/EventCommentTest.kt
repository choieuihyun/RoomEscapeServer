package com.my_dream.server.club

import com.my_dream.server.domain.ClubEventCommentRepository
import com.my_dream.server.domain.ClubMember
import com.my_dream.server.domain.ClubMemberRepository
import com.my_dream.server.domain.MemberRole
import com.my_dream.server.domain.MemberStatus
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 일정 댓글 (§4.16 · D49). 문(401·403·404) · 계약 모양 · 알림 받는 사람을 요청으로 본다 */
@SpringBootTest
@AutoConfigureMockMvc
class EventCommentTest @Autowired constructor(
    private val mvc: MockMvc,
    private val json: ObjectMapper,
    private val events: ClubEventService,
    private val members: ClubMemberRepository,
    private val comments: ClubEventCommentRepository,
    private val notifier: RecordingNotifier,
    private val jdbc: JdbcTemplate,
) {

    @TestConfiguration
    class Config {
        @Bean
        @Primary
        fun recordingNotifier() = RecordingNotifier()
    }

    @BeforeEach
    fun setUp() {
        members.deleteAll()
        members.save(ClubMember("방장", "열쇠", MemberRole.ADMIN, MemberStatus.APPROVED, Instant.now()))
        listOf("나", "너", "쟤").forEach { members.save(ClubMember(it, "닉$it", MemberRole.MEMBER, MemberStatus.APPROVED, Instant.now())) }
        members.save(ClubMember("대기", "대기", MemberRole.MEMBER, MemberStatus.PENDING, Instant.now()))
        notifier.pushes.clear()
    }

    // 댓글이 남으면 다른 테스트의 일정 지우기를 외래 키가 막는다
    @AfterEach
    fun cleanUp() {
        comments.deleteAll()
    }

    private fun as_(uid: String) = jwt().jwt { it.subject(uid) }

    private fun call(uid: String, req: MockHttpServletRequestBuilder, body: String? = null): ResultActions =
        mvc.perform(req.with(as_(uid)).let { if (body == null) it else it.contentType(MediaType.APPLICATION_JSON).content(body) })

    private fun ResultActions.err(code: Int, error: String) = andExpect(status().`is`(code)).andExpect(jsonPath("$.error").value(error))

    private fun event(title: String = "모임", segs: List<SegmentInput> = emptyList()) =
        events.create("나", EventInput(title, "2026-12-05", null, null, null, null, false, null, segs, null, null)).id

    private fun write(uid: String, eventId: Long, text: String): Long =
        json.readTree(call(uid, post("/api/club/events/$eventId/comments"), """{"text":"$text"}""").andExpect(status().isOk).andReturn().response.contentAsString)
            .get("id").asLong()

    /** 알림은 커밋 뒤 다른 스레드에서 나간다 — 올 때까지 잠깐 기다린다 */
    private fun awaitPushes(n: Int) {
        repeat(50) { if (notifier.pushes.size >= n) return; Thread.sleep(50) }
    }

    @Test
    fun `쓰고 보고 지우고 — 계약 모양, 오래된 순, commentCount, 쓴 사람·운영진만 지운다`() {
        val id = event()
        mvc.perform(get("/api/club/events/$id/comments")).andExpect(status().isUnauthorized)

        // 참여하지 않은 사람도 쓴다
        call("쟤", post("/api/club/events/$id/comments"), """{"text":"  저도 갈래요\n자리 있나요? "}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.eventId").value(id))
            .andExpect(jsonPath("$.text").value("저도 갈래요\n자리 있나요?"))
            .andExpect(jsonPath("$.author.uid").value("쟤"))
            .andExpect(jsonPath("$.author.nickname").value("닉쟤"))
            .andExpect(jsonPath("$.admin").value(false))
            .andExpect(jsonPath("$.mine").value(true))
            .andExpect(jsonPath("$.canDelete").value(true))
            .andExpect(jsonPath("$.createdAt").exists())
        val adminComment = write("방장", id, "있어요")

        call("나", get("/api/club/events/$id/comments"))
            .andExpect(jsonPath("$", hasSize<Any>(2)))
            .andExpect(jsonPath("$[0].author.uid").value("쟤"))
            .andExpect(jsonPath("$[0].mine").value(false))
            .andExpect(jsonPath("$[0].canDelete").value(false))
            .andExpect(jsonPath("$[1].id").value(adminComment))
            .andExpect(jsonPath("$[1].admin").value(true))
        call("나", get("/api/club/events/$id")).andExpect(jsonPath("$.commentCount").value(2))
        call("나", get("/api/club/events?from=2026-12-01&to=2026-12-31")).andExpect(jsonPath("$[?(@.id == $id)].commentCount").value(2))

        call("나", delete("/api/club/events/comments/$adminComment")).err(403, "NOT_AUTHOR")
        val mine = write("나", id, "내 것")
        call("나", delete("/api/club/events/comments/$mine")).andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(mine)).andExpect(jsonPath("$.deleted").value(true))
        call("방장", delete("/api/club/events/comments/$adminComment")).andExpect(status().isOk)
        call("나", delete("/api/club/events/comments/$adminComment")).err(404, "NO_SUCH_COMMENT")
        call("나", get("/api/club/events/$id")).andExpect(jsonPath("$.commentCount").value(1))

        call("대기", get("/api/club/events/$id/comments")).err(403, "NOT_CLUB_MEMBER")
        call("나", get("/api/club/events/999999/comments")).err(404, "NO_SUCH_EVENT")
        call("나", post("/api/club/events/999999/comments"), """{"text":"x"}""").err(404, "NO_SUCH_EVENT")
    }

    @Test
    fun `일정을 지우면 댓글도 지워지고, 그 일정의 댓글 경로는 404`() {
        val id = event()
        val c = write("너", id, "하나")
        events.delete("나", id)
        assertTrue(comments.findByEventIdOrderById(id).isEmpty())
        call("너", get("/api/club/events/$id/comments")).err(404, "NO_SUCH_EVENT")
        call("너", post("/api/club/events/$id/comments"), """{"text":"둘"}""").err(404, "NO_SUCH_EVENT")
        call("너", delete("/api/club/events/comments/$c")).err(404, "NO_SUCH_COMMENT")
    }

    @Test
    fun `글자 · 줄 · 깨진 본문 · 숫자가 아닌 id`() {
        val id = event()
        for (bad in listOf("""{"text":"  "}""", "{}", """{"text":"${"가".repeat(301)}"}""", """{"text":"${(1..11).joinToString("\\n")}"}""", "{깨짐")) {
            call("나", post("/api/club/events/$id/comments"), bad).err(400, "BAD_COMMENT")
                .andExpect(jsonPath("$.message").value("댓글은 1~300자, 10줄까지 적을 수 있어요."))
        }
        call("나", post("/api/club/events/$id/comments"), """{"text":"${"가".repeat(300)}"}""").andExpect(status().isOk)
        call("나", post("/api/club/events/$id/comments"), """{"text":"${(1..10).joinToString("\\n")}"}""").andExpect(status().isOk)
        call("나", get("/api/club/events/abc/comments")).err(404, "NO_SUCH_EVENT")
        call("나", delete("/api/club/events/comments/abc")).err(404, "NO_SUCH_COMMENT")
    }

    @Test
    fun `알림 — 승인된 참여자 + 만든 사람 + 댓글 쓴 사람, 쓴 사람 빼고 겹치지 않게, 일정마다 같은 tag`() {
        val id = event(title = "", segs = listOf(SegmentInput(themeName = "목격자", branchName = "건대점", startMin = 600)))
        events.join("너", id)
        // "나" 는 만든 사람이자 참여자 — 한 번만 받는다

        write("쟤", id, "${"가".repeat(70)}\\n둘째 줄")   // JSON 안의 줄바꿈 — 날 줄바꿈 문자는 JSON 이 아니다
        awaitPushes(2)
        Thread.sleep(200)
        assertEquals(listOf("나", "너"), notifier.pushes.map { it.userId }.sorted(), "참여자 · 만든 사람. 쓴 사람(쟤)은 안 받는다")
        val p = notifier.pushes.first()
        assertEquals("닉쟤 님이 댓글을 남겼어요", p.title)
        assertEquals("목격자 · ${"가".repeat(60)}…", p.body, "제목이 없으면 구간 · 첫 줄 앞 60자")
        assertEquals("#e=$id", p.linkFragment)
        assertEquals("ev-cmt-$id", p.tag)

        // 이제 쟤도 댓글을 쓴 사람이라 받는다. 대기 중인 사람은 참여자가 아니고 댓글도 못 쓰니 끼지 않는다
        notifier.pushes.clear()
        write("방장", id, "확인했어요")
        awaitPushes(3)
        Thread.sleep(200)
        assertEquals(listOf("나", "너", "쟤"), notifier.pushes.map { it.userId }.sorted())
        assertEquals("목격자 · 확인했어요", notifier.pushes.first().body)

        // 내보낸 사람은 만든 사람이어도 안 받는다
        notifier.pushes.clear()
        members.findByUserId("나")!!.let { it.status = MemberStatus.REMOVED; members.save(it) }
        write("너", id, "셋")
        awaitPushes(2)
        Thread.sleep(200)
        assertEquals(listOf("방장", "쟤"), notifier.pushes.map { it.userId }.sorted())
    }

    @Test
    fun `지난 날짜 일정에도 쓰고 알림도 간다 — 정산·후기 이야기`() {
        val id = event()
        events.join("너", id)
        jdbc.update("UPDATE club_event SET event_date = DATE '2026-01-01' WHERE id = ?", id)
        write("너", id, "정산 보냈어요")
        awaitPushes(1)
        Thread.sleep(200)
        assertEquals(listOf("나"), notifier.pushes.map { it.userId })
        assertEquals("모임 · 정산 보냈어요", notifier.pushes.single().body)
    }
}
