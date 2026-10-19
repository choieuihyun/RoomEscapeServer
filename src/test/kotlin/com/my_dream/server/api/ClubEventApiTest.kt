package com.my_dream.server.api

import com.my_dream.server.domain.ClubEventParticipantRepository
import com.my_dream.server.domain.ClubEventRepository
import com.my_dream.server.domain.ClubEventSegmentRepository
import com.my_dream.server.domain.ClubMember
import com.my_dream.server.domain.ClubMemberRepository
import com.my_dream.server.domain.MemberRole
import com.my_dream.server.domain.MemberStatus
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import kotlin.test.assertTrue

/**
 * 일정 API 의 **문** — 경로마다 로그인 · 승인 멤버 확인이 걸려 있는지, 틀린 입력도 계약 모양으로 답하는지.
 *
 * 경로 목록을 한 곳에 두고 **전부** 돈다. 새 경로를 만들고 확인을 빠뜨리면 여기서 깨진다 —
 * `ApiSecurityConfig` 끝이 `permitAll()` 이라 적지 않은 경로는 조용히 열린다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ClubEventApiTest @Autowired constructor(
    private val mvc: MockMvc,
    private val members: ClubMemberRepository,
    private val events: ClubEventRepository,
    private val segments: ClubEventSegmentRepository,
    private val participants: ClubEventParticipantRepository,
) {

    private val json = ObjectMapper()

    @BeforeEach
    fun setUp() {
        participants.deleteAll()
        segments.deleteAll()
        events.deleteAll()
        members.deleteAll()
        listOf("나", "kakao:42").forEach { members.save(ClubMember(it, "닉$it", MemberRole.MEMBER, MemberStatus.APPROVED, Instant.now())) }
        members.save(ClubMember("대기", "대기", MemberRole.MEMBER, MemberStatus.PENDING, Instant.now()))
    }

    private fun as_(uid: String) = jwt().jwt { it.subject(uid) }

    private val body = """{"title":"보드게임처럼","date":"2026-11-07","startMin":null,"endMin":null,"location":null,
        "capacity":4,"booked":false,"memo":null,"segments":[{"themeName":"ABC","branchName":"강남점","themeKey":null,"startMin":820,"endMin":890}]}"""

    private fun create(uid: String = "나"): Map<*, *> {
        val res = mvc.perform(post("/api/club/events").with(as_(uid)).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk).andReturn().response.contentAsString
        return json.readValue(res, Map::class.java)
    }

    /** 일정 API 전부. 하나 늘리면 여기에도 적는다 */
    private fun routes(id: Any): List<MockHttpServletRequestBuilder> = listOf(
        get("/api/club/events").param("from", "2026-11-01").param("to", "2026-11-30"),
        get("/api/club/events/$id"),
        post("/api/club/events").contentType(MediaType.APPLICATION_JSON).content(body),
        post("/api/club/events/$id").contentType(MediaType.APPLICATION_JSON).content(body),
        delete("/api/club/events/$id"),
        post("/api/club/events/$id/join"),
        post("/api/club/events/$id/leave"),
        post("/api/club/events/$id/participants").contentType(MediaType.APPLICATION_JSON).content("""{"uid":"kakao:42"}"""),
        delete("/api/club/events/$id/participants/kakao:42"),
        post("/api/club/events/$id/booking").contentType(MediaType.APPLICATION_JSON).content("""{"booked":true}"""),
        get("/api/club/members"),
    )

    @Test
    fun `토큰 없이는 전부 401`() {
        val id = create()["id"]!!
        routes(id).forEach { mvc.perform(it).andExpect(status().isUnauthorized) }
    }

    @Test
    fun `승인 안 된 사람은 전부 403 NOT_CLUB_MEMBER`() {
        val id = create()["id"]!!
        routes(id).forEach {
            mvc.perform(it.with(as_("대기")))
                .andExpect(status().isForbidden)
                .andExpect(jsonPath("$.error").value("NOT_CLUB_MEMBER"))
                .andExpect(jsonPath("$.message").value("동호회 승인을 받은 계정만 일정을 볼 수 있어요."))
        }
    }

    @Test
    fun `만들면 계약 모양으로 돌아온다`() {
        mvc.perform(post("/api/club/events").with(as_("나")).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.date").value("2026-11-07"))
            .andExpect(jsonPath("$.segments[0].themeName").value("ABC"))
            .andExpect(jsonPath("$.createdBy.uid").value("나"))
            .andExpect(jsonPath("$.participants[0].uid").value("나"))
            .andExpect(jsonPath("$.joined").value(true))
            .andExpect(jsonPath("$.canManage").value(true))
            // canDelete 는 계약에서 빠졌다 (canManage 로 바뀜)
            .andExpect(jsonPath("$.canDelete").doesNotExist())
            .andExpect(content().string(containsString("\"startMin\":null")))
    }

    @Test
    fun `읽을 수 없는 본문도 BAD_EVENT 본문으로`() {
        // 스프링 기본 400 은 error·message 가 없다 — 화면은 그 경우 문구를 못 띄운다
        for (bad in listOf("""{"date":"2026-11-07","booked":"아마도"}""", """{"date":""", """[1,2]""")) {
            mvc.perform(post("/api/club/events").with(as_("나")).contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.error").value("BAD_EVENT"))
        }
    }

    @Test
    fun `날짜 범위가 틀리면 BAD_RANGE 본문으로`() {
        mvc.perform(get("/api/club/events").with(as_("나")).param("from", "어제").param("to", "2026-11-30"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("BAD_RANGE"))
        mvc.perform(get("/api/club/events").with(as_("나")))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("BAD_RANGE"))
    }

    @Test
    fun `숫자가 아닌 일정 id 는 NO_SUCH_EVENT`() {
        mvc.perform(get("/api/club/events/abc").with(as_("나")))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error").value("NO_SUCH_EVENT"))
    }

    @Test
    fun `충돌하면 본문에 최신 일정이 같이 온다`() {
        val made = create()
        val id = made["id"]
        val v = (made["version"] as Number).toLong()
        val edit = { title: String -> body.replace("보드게임처럼", title).replace("}]}", "}],\"version\":$v}") }
        mvc.perform(post("/api/club/events/$id").with(as_("나")).contentType(MediaType.APPLICATION_JSON).content(edit("앞 사람")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.version").value(v + 1))
        mvc.perform(post("/api/club/events/$id").with(as_("나")).contentType(MediaType.APPLICATION_JSON).content(edit("뒷 사람")))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("EVENT_CONFLICT"))
            .andExpect(jsonPath("$.message").value("다른 사람이 먼저 고쳤어요. 새로 불러온 내용을 확인해 주세요."))
            .andExpect(jsonPath("$.event.title").value("앞 사람"))
            .andExpect(jsonPath("$.event.version").value(v + 1))
    }

    @Test
    fun `시작만 있는 일정은 200, 끝만 있는 일정은 400 BAD_EVENT`() {
        val startOnly = body.replace("\"startMin\":null,\"endMin\":null", "\"startMin\":1140,\"endMin\":null")
        val res = mvc.perform(post("/api/club/events").with(as_("나")).contentType(MediaType.APPLICATION_JSON).content(startOnly))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.startMin").value(1140))
            .andReturn().response.contentAsString
        val made = json.readValue(res, Map::class.java)
        assertTrue(made.containsKey("endMin") && made["endMin"] == null, "endMin 은 빠지지 않고 null 로 온다")
        val edit = startOnly.replace("1140", "1170").replace("}]}", "}],\"version\":${made["version"]}}")
        mvc.perform(post("/api/club/events/${made["id"]}").with(as_("나")).contentType(MediaType.APPLICATION_JSON).content(edit))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.startMin").value(1170))

        val endOnly = body.replace("\"startMin\":null,\"endMin\":null", "\"startMin\":null,\"endMin\":1260")
        mvc.perform(post("/api/club/events").with(as_("나")).contentType(MediaType.APPLICATION_JSON).content(endOnly))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("BAD_EVENT"))
    }

    @Test
    fun `받은 EventDto 를 고쳐 그대로 돌려보내도 수정이 된다`() {
        // 화면은 흔히 받은 응답(id·participants·canManage·count…)을 고쳐서 통째로 보낸다.
        // 모르는 필드를 거절하면 그 요청이 전부 BAD_EVENT 가 된다 — 서버 테스트가 입력 필드만 보내면 이걸 못 잡는다
        val made = create()
        val sent = (made + mapOf("title" to "통째로 고침")).toMutableMap()
        mvc.perform(post("/api/club/events/${made["id"]}").with(as_("나")).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(sent)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.title").value("통째로 고침"))
    }

    @Test
    fun `카카오 uid 를 경로로 대신 빼고, 지운 일정은 두 번째에 404`() {
        val id = create()["id"]
        mvc.perform(post("/api/club/events/$id/participants").with(as_("나")).contentType(MediaType.APPLICATION_JSON).content("""{"uid":"kakao:42"}"""))
            .andExpect(jsonPath("$.count").value(2))
        mvc.perform(delete("/api/club/events/$id/participants/kakao:42").with(as_("나")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.count").value(1))
        mvc.perform(delete("/api/club/events/$id").with(as_("나")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.deleted").value(true))
        mvc.perform(request(HttpMethod.DELETE, "/api/club/events/$id").with(as_("나")))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error").value("NO_SUCH_EVENT"))
    }
}
