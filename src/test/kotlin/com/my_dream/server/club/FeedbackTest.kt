package com.my_dream.server.club

import com.my_dream.server.domain.ClubFeedbackCommentRepository
import com.my_dream.server.domain.ClubFeedbackLikeRepository
import com.my_dream.server.domain.ClubFeedbackRepository
import com.my_dream.server.domain.ClubMember
import com.my_dream.server.domain.ClubMemberRepository
import com.my_dream.server.domain.MemberRole
import com.my_dream.server.domain.MemberStatus
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import kotlin.test.assertEquals

/** 의견 게시판 (§4.15 · D47). 문(401·403·404)과 계약 모양을 요청으로 본다 */
@SpringBootTest
@AutoConfigureMockMvc
class FeedbackTest @Autowired constructor(
    private val mvc: MockMvc,
    private val json: ObjectMapper,
    private val members: ClubMemberRepository,
    private val feedbacks: ClubFeedbackRepository,
    private val likes: ClubFeedbackLikeRepository,
    private val comments: ClubFeedbackCommentRepository,
) {

    @BeforeEach
    fun setUp() {
        likes.deleteAll(); comments.deleteAll(); feedbacks.deleteAll()
        members.deleteAll()
        members.save(ClubMember("방장", "열쇠", MemberRole.ADMIN, MemberStatus.APPROVED, Instant.now()))
        members.save(ClubMember("나", "닉나", MemberRole.MEMBER, MemberStatus.APPROVED, Instant.now()))
        members.save(ClubMember("너", "닉너", MemberRole.MEMBER, MemberStatus.APPROVED, Instant.now()))
        members.save(ClubMember("대기", "대기", MemberRole.MEMBER, MemberStatus.PENDING, Instant.now()))
    }

    private fun as_(uid: String) = jwt().jwt { it.subject(uid) }

    private fun call(uid: String, req: MockHttpServletRequestBuilder, body: String? = null): ResultActions =
        mvc.perform(req.with(as_(uid)).let { if (body == null) it else it.contentType(MediaType.APPLICATION_JSON).content(body) })

    private fun write(uid: String, text: String): Long =
        json.readTree(call(uid, post("/api/club/feedback"), """{"text":"$text"}""").andExpect(status().isOk).andReturn().response.contentAsString)
            .get("id").asLong()

    private fun ResultActions.err(code: Int, error: String) = andExpect(status().`is`(code)).andExpect(jsonPath("$.error").value(error))

    @Test
    fun `쓰고 보고 고치고 — 계약 모양, 최근 글 순, updatedAt 은 글을 고칠 때만`() {
        mvc.perform(get("/api/club/feedback")).andExpect(status().isUnauthorized)

        call("나", post("/api/club/feedback"), """{"text":"  다크 모드\n있으면 좋겠어요 "}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.text").value("다크 모드\n있으면 좋겠어요"))
            .andExpect(jsonPath("$.status").value("OPEN"))
            .andExpect(jsonPath("$.author.uid").value("나"))
            .andExpect(jsonPath("$.author.nickname").value("닉나"))
            .andExpect(jsonPath("$.likes").value(0))
            .andExpect(jsonPath("$.liked").value(false))
            .andExpect(jsonPath("$.comments").value(0))
            .andExpect(jsonPath("$.mine").value(true))
            .andExpect(jsonPath("$.canDelete").value(true))
            // 키는 있고 값이 null — 화면의 타입(`ISO|null`)이 거짓말하지 않게
            .andExpect(content().string(org.hamcrest.Matchers.containsString("\"statusAt\":null")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("\"statusBy\":null")))
        val second = write("너", "둘째")

        call("나", get("/api/club/feedback"))
            .andExpect(jsonPath("$", hasSize<Any>(2)))
            .andExpect(jsonPath("$[0].id").value(second))
            .andExpect(jsonPath("$[0].mine").value(false))
            .andExpect(jsonPath("$[0].canDelete").value(false))
        call("방장", get("/api/club/feedback")).andExpect(jsonPath("$[0].canDelete").value(true))

        val before = feedbacks.findById(second).get().updatedAt
        call("방장", put("/api/club/feedback/$second/status"), """{"status":"REVIEW"}""")
        call("방장", post("/api/club/feedback/$second/like"), """{"liked":true}""")
        call("방장", post("/api/club/feedback/$second/comments"), """{"text":"볼게요"}""")
        assertEquals(before, feedbacks.findById(second).get().updatedAt, "상태·공감·댓글로는 안 바뀐다")
        call("너", put("/api/club/feedback/$second"), """{"text":"고친 둘째"}""").andExpect(jsonPath("$.text").value("고친 둘째"))
        assert(feedbacks.findById(second).get().updatedAt > before)

        call("나", put("/api/club/feedback/$second"), """{"text":"남의 글"}""").err(403, "NOT_AUTHOR")
        call("방장", put("/api/club/feedback/$second"), """{"text":"운영진도 못 고친다"}""").err(403, "NOT_AUTHOR")
        call("나", put("/api/club/feedback/999999"), """{"text":"x"}""").err(404, "NO_SUCH_FEEDBACK")
        call("대기", get("/api/club/feedback")).err(403, "NOT_CLUB_MEMBER")
    }

    @Test
    fun `글자 · 줄 · 깨진 본문 · 숫자가 아닌 id`() {
        for (bad in listOf("""{"text":"  "}""", "{}", """{"text":"${"가".repeat(1001)}"}""", """{"text":"${(1..21).joinToString("\\r\\n")}"}""", "{깨짐")) {
            call("나", post("/api/club/feedback"), bad).err(400, "BAD_FEEDBACK").andExpect(jsonPath("$.message").value("의견은 1~1000자, 20줄까지 적을 수 있어요."))
        }
        call("나", post("/api/club/feedback"), """{"text":"${"가".repeat(1000)}"}""").andExpect(status().isOk)
        val id = write("나", (1..20).joinToString("\\n"))

        for (bad in listOf("""{"text":""}""", """{"text":"${"가".repeat(301)}"}""", "{깨짐")) {
            call("너", post("/api/club/feedback/$id/comments"), bad).err(400, "BAD_COMMENT").andExpect(jsonPath("$.message").value("댓글은 1~300자까지 적을 수 있어요."))
        }
        call("너", post("/api/club/feedback/$id/comments"), """{"text":"${"가".repeat(300)}"}""").andExpect(status().isOk)

        call("나", get("/api/club/feedback/abc/comments")).err(404, "NO_SUCH_FEEDBACK")
        call("나", delete("/api/club/feedback/comments/abc")).err(404, "NO_SUCH_COMMENT")
    }

    @Test
    fun `공감 — 멱등, 사람마다 하나, 자기 글도 된다`() {
        val id = write("나", "공감 받을 글")
        call("나", post("/api/club/feedback/$id/like"), """{"liked":true}""").andExpect(jsonPath("$.likes").value(1)).andExpect(jsonPath("$.liked").value(true))
        call("나", post("/api/club/feedback/$id/like"), """{"liked":true}""").andExpect(jsonPath("$.likes").value(1))
        call("너", post("/api/club/feedback/$id/like"), """{"liked":true}""").andExpect(jsonPath("$.likes").value(2))
        call("나", post("/api/club/feedback/$id/like"), """{"liked":false}""").andExpect(jsonPath("$.likes").value(1)).andExpect(jsonPath("$.liked").value(false))
        call("나", post("/api/club/feedback/$id/like"), """{"liked":false}""").andExpect(jsonPath("$.likes").value(1))
        call("너", get("/api/club/feedback")).andExpect(jsonPath("$[0].liked").value(true)).andExpect(jsonPath("$[0].likes").value(1))
        call("나", post("/api/club/feedback/999999/like"), """{"liked":true}""").err(404, "NO_SUCH_FEEDBACK")
    }

    @Test
    fun `상태는 운영진만, 아무 순서로, OPEN 으로 되돌리면 언제·누가가 빈다`() {
        val id = write("나", "상태 볼 글")
        call("나", put("/api/club/feedback/$id/status"), """{"status":"DONE"}""").err(403, "NOT_ADMIN")
        for (bad in listOf("""{"status":"WHAT"}""", "{}", """{"status":"done"}""", "{깨짐")) {
            call("방장", put("/api/club/feedback/$id/status"), bad).err(400, "BAD_STATUS").andExpect(jsonPath("$.message").value("처리 상태를 다시 골라 주세요."))
        }
        call("방장", put("/api/club/feedback/$id/status"), """{"status":"DONE"}""")
            .andExpect(jsonPath("$.status").value("DONE"))
            .andExpect(jsonPath("$.statusAt").exists())
            .andExpect(jsonPath("$.statusBy.uid").value("방장"))
            .andExpect(jsonPath("$.statusBy.nickname").value("열쇠"))
        call("방장", put("/api/club/feedback/$id/status"), """{"status":"HOLD"}""").andExpect(jsonPath("$.status").value("HOLD"))
        call("방장", put("/api/club/feedback/$id/status"), """{"status":"OPEN"}""")
            .andExpect(jsonPath("$.status").value("OPEN"))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("\"statusAt\":null")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("\"statusBy\":null")))
        call("방장", put("/api/club/feedback/999999/status"), """{"status":"DONE"}""").err(404, "NO_SUCH_FEEDBACK")
    }

    @Test
    fun `댓글 — 오래된 순, 운영진 표시는 쓸 때 값, 지우기는 쓴 사람·운영진`() {
        val id = write("나", "댓글 달 글")
        val c1 = json.readTree(call("방장", post("/api/club/feedback/$id/comments"), """{"text":"검토할게요"}""")
            .andExpect(jsonPath("$.admin").value(true)).andExpect(jsonPath("$.feedbackId").value(id)).andReturn().response.contentAsString).get("id").asLong()
        val c2 = json.readTree(call("너", post("/api/club/feedback/$id/comments"), """{"text":"저도요"}""")
            .andExpect(jsonPath("$.admin").value(false)).andReturn().response.contentAsString).get("id").asLong()

        // 방장이 운영진에서 내려와도 그 댓글의 표시는 그대로 (저장 값)
        members.findByUserId("방장")!!.also { it.role = MemberRole.MEMBER; members.save(it) }
        call("너", get("/api/club/feedback/$id/comments"))
            .andExpect(jsonPath("$", hasSize<Any>(2)))
            .andExpect(jsonPath("$[0].id").value(c1))
            .andExpect(jsonPath("$[0].admin").value(true))
            .andExpect(jsonPath("$[0].author.nickname").value("열쇠"))
            .andExpect(jsonPath("$[1].mine").value(true))
            .andExpect(jsonPath("$[0].canDelete").value(false))
        call("나", get("/api/club/feedback")).andExpect(jsonPath("$[0].comments").value(2))

        call("나", delete("/api/club/feedback/comments/$c2")).err(403, "NOT_AUTHOR")
        members.findByUserId("방장")!!.also { it.role = MemberRole.ADMIN; members.save(it) }
        call("방장", delete("/api/club/feedback/comments/$c2")).andExpect(jsonPath("$.id").value(c2)).andExpect(jsonPath("$.deleted").value(true))
        call("방장", delete("/api/club/feedback/comments/$c2")).err(404, "NO_SUCH_COMMENT")
        call("나", post("/api/club/feedback/999999/comments"), """{"text":"x"}""").err(404, "NO_SUCH_FEEDBACK")
    }

    @Test
    fun `글을 지우면 댓글·공감도 사라지고 이후 404 — 지우기는 쓴 사람·운영진`() {
        val mine = write("나", "지울 글")
        call("너", post("/api/club/feedback/$mine/like"), """{"liked":true}""")
        call("너", post("/api/club/feedback/$mine/comments"), """{"text":"댓글"}""")
        call("너", delete("/api/club/feedback/$mine")).err(403, "NOT_AUTHOR")
        call("나", delete("/api/club/feedback/$mine")).andExpect(jsonPath("$.id").value(mine)).andExpect(jsonPath("$.deleted").value(true))
        assertEquals(0, likes.count())
        assertEquals(0, comments.count())
        call("나", delete("/api/club/feedback/$mine")).err(404, "NO_SUCH_FEEDBACK")
        call("나", get("/api/club/feedback/$mine/comments")).err(404, "NO_SUCH_FEEDBACK")

        val other = write("너", "운영진이 지울 글")
        call("방장", delete("/api/club/feedback/$other")).andExpect(status().isOk)
        call("나", get("/api/club/feedback")).andExpect(jsonPath("$", hasSize<Any>(0)))
    }
}
