package com.my_dream.server.api

import com.my_dream.server.domain.ClubEventAvailabilityRepository
import com.my_dream.server.domain.ClubWishRepository
import com.my_dream.server.domain.PayInfoRepository
import com.my_dream.server.domain.SettlementItemRepository
import com.my_dream.server.domain.SettlementRepository
import com.my_dream.server.domain.SettlementShareRepository
import com.my_dream.server.domain.SettlementTransferRepository
import com.my_dream.server.domain.ClubEventCandidateRepository
import com.my_dream.server.domain.ClubEventParticipantRepository
import com.my_dream.server.domain.ClubEventRepository
import com.my_dream.server.domain.ClubEventSegmentRepository
import com.my_dream.server.domain.ClubMember
import com.my_dream.server.domain.ClubMemberRepository
import com.my_dream.server.domain.MemberProfileRepository
import com.my_dream.server.domain.MemberRole
import com.my_dream.server.domain.MemberStatus
import com.my_dream.server.domain.PlayedThemeRepository
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
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
    private val candidates: ClubEventCandidateRepository,
    private val played: PlayedThemeRepository,
    private val answers: ClubEventAvailabilityRepository,
    private val profiles: MemberProfileRepository,
    private val wishes: ClubWishRepository,
    private val settlementHeads: SettlementRepository,
    private val settlementItems: SettlementItemRepository,
    private val settlementShares: SettlementShareRepository,
    private val settlementPaid: SettlementTransferRepository,
    private val payInfos: PayInfoRepository,
) {

    private val json = ObjectMapper()

    @BeforeEach
    fun setUp() {
        settlementPaid.deleteAll()
        settlementShares.deleteAll()
        settlementItems.deleteAll()
        settlementHeads.deleteAll()
        wishes.deleteAll()
        payInfos.deleteAll()
        played.deleteAll()
        profiles.deleteAll()
        answers.deleteAll()
        candidates.deleteAll()
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

    /**
     * 동호회 API 전부 (일정 · 시간 협의 · 해 본 테마 · 지난 일정 · 랭킹 · 하고 싶은 테마 · 정산 · 송금 정보). 하나 늘리면 여기에도 적는다.
     * `PUT /api/me/profile` 은 여기 없다 — 대기 중인 사람도 쓰는 경로라 NOT_CLUB_MEMBER 가 아니다(아래 따로)
     */
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
        post("/api/club/events/$id/availability").contentType(MediaType.APPLICATION_JSON).content("""{"date":"2026-11-07","available":true}"""),
        delete("/api/club/events/$id/availability/2026-11-07"),
        get("/api/club/events/$id/fresh-themes"),
        get("/api/me/played"),
        post("/api/me/played").contentType(MediaType.APPLICATION_JSON).content("""{"themeName":"목격자"}"""),
        delete("/api/me/played/1"),
        get("/api/club/members/kakao:42/played"),
        get("/api/me/events"),
        get("/api/club/rankings").param("from", "2026-10").param("to", "2026-10"),
        get("/api/me/wishes"),
        post("/api/me/wishes").contentType(MediaType.APPLICATION_JSON).content("""{"themeName":"목격자"}"""),
        delete("/api/me/wishes/1"),
        get("/api/club/wishes"),
        get("/api/club/events/$id/settlement"),
        put("/api/club/events/$id/settlement").contentType(MediaType.APPLICATION_JSON).content("""{"version":0,"items":[]}"""),
        post("/api/club/events/$id/settlement/transfers").contentType(MediaType.APPLICATION_JSON).content("""{"fromUid":"a","toUid":"b","paid":true}"""),
        get("/api/me/payinfo"),
        put("/api/me/payinfo").contentType(MediaType.APPLICATION_JSON).content("""{"bankText":null,"kakaopayUrl":null}"""),
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
                .andExpect(jsonPath("$.message").value("가입 승인을 받은 계정만 일정을 볼 수 있어요."))
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
    fun `날짜 미정 일정의 EventDto 를 통째로 돌려보내도 수정되고 후보·응답이 남는다`() {
        // 응답의 candidates 는 객체 목록({date, responses}), 입력은 날짜 문자열 목록이다. 받은 것을 그대로 돌려보내는 화면이
        // 모양이 달라 BAD_EVENT 를 받으면 안 된다 — 2단계 화면이 이렇게 보낸다 (§4.7 "통째로 돌려보내도")
        val undecided = body.replace("\"date\":\"2026-11-07\"", "\"date\":null,\"candidates\":[\"2026-11-07\",\"2026-11-08\"]")
        val res = mvc.perform(post("/api/club/events").with(as_("나")).contentType(MediaType.APPLICATION_JSON).content(undecided))
            .andExpect(status().isOk).andReturn().response.contentAsString
        val made = json.readValue(res, Map::class.java)
        mvc.perform(
            post("/api/club/events/${made["id"]}/availability").with(as_("나")).contentType(MediaType.APPLICATION_JSON)
                .content("""{"date":"2026-11-08","available":true,"startMin":1140,"endMin":1320}"""),
        ).andExpect(status().isOk).andExpect(jsonPath("$.version").value(made["version"]))

        val latest = json.readValue(mvc.perform(get("/api/club/events/${made["id"]}").with(as_("나"))).andReturn().response.contentAsString, Map::class.java)
        val sent = (latest + mapOf("title" to "통째로 고침")).toMutableMap()
        mvc.perform(post("/api/club/events/${made["id"]}").with(as_("나")).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(sent)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.title").value("통째로 고침"))
            .andExpect(jsonPath("$.date").isEmpty)
            .andExpect(jsonPath("$.candidates[1].date").value("2026-11-08"))
            .andExpect(jsonPath("$.candidates[1].responses[0].startMin").value(1140))
    }

    @Test
    fun `응답 경로의 틀린 입력은 BAD_AVAILABILITY 본문으로`() {
        val id = create()["id"]
        for (bad in listOf("{", """{"date":"2026-11-07","available":"아마"}""")) {
            mvc.perform(post("/api/club/events/$id/availability").with(as_("나")).contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.error").value("BAD_AVAILABILITY"))
        }
        mvc.perform(delete("/api/club/events/$id/availability/어제").with(as_("나")))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("BAD_AVAILABILITY"))
        mvc.perform(post("/api/club/events/$id/availability").with(as_("나")).contentType(MediaType.APPLICATION_JSON).content("""{"date":"2026-11-07","available":true}"""))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("NOT_A_CANDIDATE"))
            .andExpect(jsonPath("$.message").value("후보 날짜가 아니에요. 새로 불러와 주세요."))
    }

    @Test
    fun `구간에 playedBy 가 실린 EventDto 를 통째로 돌려보내도 수정된다`() {
        // 입력 검사도 SegmentDto 를 쓴다 — playedBy 에 기본값이 없거나 입력이 그 필드를 거절하면 여기서 BAD_EVENT 가 난다
        val made = create()
        mvc.perform(post("/api/me/played").with(as_("kakao:42")).contentType(MediaType.APPLICATION_JSON).content("""{"themeName":"ABC"}"""))
            .andExpect(status().isOk)
        mvc.perform(post("/api/club/events/${made["id"]}/join").with(as_("kakao:42"))).andExpect(status().isOk)
        val latest = json.readValue(mvc.perform(get("/api/club/events/${made["id"]}").with(as_("나"))).andReturn().response.contentAsString, Map::class.java)
        assertTrue(((latest["segments"] as List<*>).single() as Map<*, *>)["playedBy"] == listOf("kakao:42"), "$latest")

        mvc.perform(post("/api/club/events/${made["id"]}").with(as_("나")).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(latest + mapOf("title" to "고침"))))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.segments[0].playedBy[0]").value("kakao:42"))
    }

    @Test
    fun `해 본 테마·지난 일정 경로의 틀린 입력도 계약 본문으로`() {
        mvc.perform(post("/api/me/played").with(as_("나")).contentType(MediaType.APPLICATION_JSON).content("{"))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.error").value("BAD_PLAYED"))
        mvc.perform(delete("/api/me/played/abc").with(as_("나")))
            .andExpect(status().isNotFound).andExpect(jsonPath("$.error").value("NO_SUCH_PLAYED"))
        mvc.perform(get("/api/me/events").with(as_("나")))
            .andExpect(status().isOk).andExpect(jsonPath("$.events").isArray).andExpect(jsonPath("$.total").value(0))
        mvc.perform(get("/api/me/events").param("beforeId", "3").with(as_("나")))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.error").value("BAD_RANGE"))
        mvc.perform(get("/api/me/events").param("limit", "많이").with(as_("나")))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.error").value("BAD_RANGE"))
        mvc.perform(get("/api/club/events/abc/fresh-themes").with(as_("나")))
            .andExpect(status().isNotFound).andExpect(jsonPath("$.error").value("NO_SUCH_EVENT"))
    }

    @Test
    fun `테스트 알림은 로그인만 있으면 되고 1분에 한 번, 넘으면 429 TOO_SOON 본문`() {
        mvc.perform(post("/api/me/push-test")).andExpect(status().isUnauthorized)
        mvc.perform(post("/api/me/push-test").with(as_("대기")))   // 승인 전이어도 된다
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.devices").isNumber)
            .andExpect(jsonPath("$.delivery").doesNotExist())
        mvc.perform(post("/api/me/push-test").with(as_("대기")))
            .andExpect(status().isTooManyRequests)
            .andExpect(jsonPath("$.error").value("TOO_SOON"))
            .andExpect(jsonPath("$.message").value("1분 뒤에 다시 보내 주세요."))
    }

    @Test
    fun `이 기기 상태 — 내 토큰일 때만 true`() {
        val status = { uid: String, body: String ->
            mvc.perform(post("/api/devices/status").with(as_(uid)).contentType(MediaType.APPLICATION_JSON).content(body))
        }
        mvc.perform(post("/api/devices/status").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized)
        mvc.perform(post("/api/devices").with(as_("나")).contentType(MediaType.APPLICATION_JSON).content("""{"token":"tok-나-1"}"""))
            .andExpect(status().isNoContent)
        status("나", """{"token":"tok-나-1"}""").andExpect(jsonPath("$.registered").value(true))
        status("너", """{"token":"tok-나-1"}""").andExpect(jsonPath("$.registered").value(false))
        status("나", "{}").andExpect(jsonPath("$.registered").value(false))
    }

    // --- 6단계 프로필 · 랭킹 (§4.11) ---

    private val profileJson = """{"realName":"김철수","birthDate":"1997-06-28","mbti":"INTJ","roomCount":120,
        "genres":["공포","추리"],"lifeTheme":{"themeName":"비밀의 화원","branchName":null,"themeKey":null}}"""

    private fun putProfile(uid: String?, content: String) =
        mvc.perform(put("/api/me/profile").apply { if (uid != null) with(as_(uid)) }.contentType(MediaType.APPLICATION_JSON).content(content))

    @Test
    fun `프로필 고치기 — 401, 신청 전이면 NOT_JOINED, 대기 중이면 된다`() {
        putProfile(null, """{"nickname":"철수","profile":$profileJson}""").andExpect(status().isUnauthorized)
        putProfile("모름", """{"nickname":"철수","profile":$profileJson}""")
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error").value("NOT_JOINED"))
            .andExpect(jsonPath("$.message").value("가입 신청한 계정만 프로필을 고칠 수 있어요."))
        putProfile("대기", """{"nickname":"철수","profile":$profileJson}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.nickname").value("철수"))
            .andExpect(jsonPath("$.profile.birthDate").value("1997-06-28"))
            .andExpect(jsonPath("$.profile.genres[1]").value("추리"))
            .andExpect(jsonPath("$.profile.lifeTheme.themeName").value("비밀의 화원"))
            // 안 보낸 거주지역은 **키가 있고 null** — 화면이 키를 보고 "채워 주세요" 줄을 켠다
            .andExpect(content().string(org.hamcrest.Matchers.containsString(""""residence":null""")))
    }

    @Test
    fun `거주지역 — 저장되면 멤버 목록에 실리고, 21자는 BAD_PROFILE 문구로`() {
        putProfile("나", """{"nickname":"닉나","profile":${profileJson.dropLast(1)},"residence":" 서울 마포구 "}}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.profile.residence").value("서울 마포구"))
        mvc.perform(get("/api/club/members").with(as_("나")))
            .andExpect(jsonPath("$[?(@.uid=='나')].profile.residence").value("서울 마포구"))
        putProfile("나", """{"nickname":"닉나","profile":${profileJson.dropLast(1)},"residence":"${"가".repeat(21)}"}}""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("BAD_PROFILE"))
            .andExpect(jsonPath("$.message").value("거주지역은 20자까지 적어 주세요."))
    }

    @Test
    fun `프로필의 틀린 타입 · 깨진 본문도 BAD_PROFILE 본문으로`() {
        // 잭슨이 정수 칸의 1.5 를 1 로, "12" 를 12 로 바꾸면 검사를 지나친다 — 느슨하게 받아 서버가 본다 (D35)
        for (bad in listOf("1.5", "\"12\"")) {
            putProfile("나", """{"nickname":"철수","profile":${profileJson.replace("120", bad)}}""")
                .andExpect(status().isBadRequest).andExpect(jsonPath("$.error").value("BAD_PROFILE"))
        }
        putProfile("나", """{"nickname":"철수","profile":{"lifeTheme":"비밀의 화원"}}""")
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.error").value("BAD_PROFILE"))
        putProfile("나", "{").andExpect(status().isBadRequest).andExpect(jsonPath("$.error").value("BAD_PROFILE"))
        mvc.perform(post("/api/me/join").with(as_("새사람")).contentType(MediaType.APPLICATION_JSON).content("""{"nickname":"철수","profile":{"genres":"공포"}}"""))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.error").value("BAD_PROFILE"))
    }

    @Test
    fun `옛 앱의 가입 신청(닉네임만)은 그대로 되고 profile 은 null 로 실린다`() {
        mvc.perform(post("/api/me/join").with(as_("새사람")).contentType(MediaType.APPLICATION_JSON).content("""{"nickname":"철수"}"""))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.profile").value(org.hamcrest.Matchers.nullValue()))
    }

    @Test
    fun `멤버 목록에 역할 · 승인일 · 프로필이 실리고 랭킹은 계약 모양으로`() {
        putProfile("나", """{"nickname":"닉나","profile":$profileJson}""").andExpect(status().isOk)
        mvc.perform(get("/api/club/members").with(as_("나")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[?(@.uid=='나')].role").value("MEMBER"))
            .andExpect(jsonPath("$[?(@.uid=='나')].profile.realName").value("김철수"))
            .andExpect(jsonPath("$[?(@.uid=='kakao:42')].profile").value(org.hamcrest.Matchers.contains(org.hamcrest.Matchers.nullValue())))
        mvc.perform(get("/api/club/rankings").param("from", "2026-09").param("to", "2026-10").with(as_("나")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].month").value("2026-10"))
            .andExpect(jsonPath("$[1].ranks").isArray)
        mvc.perform(get("/api/club/rankings").param("from", "2026-1").param("to", "2026-10").with(as_("나")))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("BAD_RANGE"))
            .andExpect(jsonPath("$.message").value("랭킹은 한 번에 12달까지 볼 수 있어요."))
    }

    @Test
    fun `테마 고르기 목록은 로그인 없이 열린다`() {
        mvc.perform(get("/api/themes")).andExpect(status().isOk)
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

    // --- 7단계 하고 싶은 테마 · 정산 · 송금 정보 (§4.12 · §4.13) ---

    @Test
    fun `깨진 본문과 틀린 경로 id 도 경로마다 계약 코드로`() {
        val id = create()["id"]
        fun bad(req: MockHttpServletRequestBuilder, status: Int, code: String) =
            mvc.perform(req.with(as_("나"))).andExpect(status().`is`(status)).andExpect(jsonPath("$.error").value(code))
        bad(post("/api/me/wishes").contentType(MediaType.APPLICATION_JSON).content("{"), 400, "BAD_WISH")
        bad(put("/api/me/payinfo").contentType(MediaType.APPLICATION_JSON).content("""{"bankText":{}}"""), 400, "BAD_PAYINFO")
        bad(put("/api/club/events/$id/settlement").contentType(MediaType.APPLICATION_JSON).content("{"), 400, "BAD_SETTLEMENT")
        bad(post("/api/club/events/$id/settlement/transfers").contentType(MediaType.APPLICATION_JSON).content("""{"paid":"아마도"}"""), 400, "BAD_SETTLEMENT")
        // 잭슨이 숫자로 바꿔 줄 수 있는 문자열 금액도 HTTP 를 거쳐 거절된다 (금액 칸이 Any? 인 이유)
        bad(
            put("/api/club/events/$id/settlement").contentType(MediaType.APPLICATION_JSON)
                .content("""{"version":0,"items":[{"label":"밥","amount":"1000","payerUid":"나","shares":[{"uid":"나","amount":1000}]}]}"""),
            400, "BAD_SETTLEMENT",
        )
        bad(delete("/api/me/wishes/abc"), 404, "NO_SUCH_WISH")
        bad(get("/api/club/events/abc/settlement"), 404, "NO_SUCH_EVENT")
    }

    @Test
    fun `정산 모양 — 저장 · 충돌 본문의 settlement · 일정 목록의 settlementTotal`() {
        val id = create()["id"]
        mvc.perform(put("/api/me/payinfo").with(as_("나")).contentType(MediaType.APPLICATION_JSON)
            .content("""{"bankText":"카카오뱅크 3333-01","kakaopayUrl":"https://qr.kakaopay.com/x"}"""))
            .andExpect(status().isOk).andExpect(jsonPath("$.bankText").value("카카오뱅크 3333-01"))
        mvc.perform(post("/api/club/events/$id/participants").with(as_("나")).contentType(MediaType.APPLICATION_JSON).content("""{"uid":"kakao:42"}"""))
        val save = """{"version":0,"items":[{"label":"방탈출","amount":50000,"payerUid":"나","shares":[{"uid":"나","amount":25000},{"uid":"kakao:42","amount":25000}]}]}"""
        mvc.perform(put("/api/club/events/$id/settlement").with(as_("kakao:42")).contentType(MediaType.APPLICATION_JSON).content(save))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.eventId").value(id!!))
            .andExpect(jsonPath("$.version").value(1))
            .andExpect(jsonPath("$.updatedBy.nickname").value("닉kakao:42"))
            .andExpect(jsonPath("$.items[0].payer.uid").value("나"))
            .andExpect(jsonPath("$.items[0].shares[1].nickname").value("닉kakao:42"))
            .andExpect(jsonPath("$.total").value(50000))
            .andExpect(jsonPath("$.transfers[0].from.uid").value("kakao:42"))
            .andExpect(jsonPath("$.transfers[0].amount").value(25000))
            .andExpect(jsonPath("$.transfers[0].paid").value(false))
            .andExpect(jsonPath("$.transfers[0].paidAt").isEmpty)
            .andExpect(jsonPath("$.payInfo['나'].kakaopayUrl").value("https://qr.kakaopay.com/x"))
        mvc.perform(put("/api/club/events/$id/settlement").with(as_("나")).contentType(MediaType.APPLICATION_JSON).content(save))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("SETTLEMENT_CONFLICT"))
            .andExpect(jsonPath("$.settlement.version").value(1))
        mvc.perform(post("/api/club/events/$id/settlement/transfers").with(as_("kakao:42")).contentType(MediaType.APPLICATION_JSON)
            .content("""{"fromUid":"kakao:42","toUid":"나","paid":true}"""))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.transfers[0].paid").value(true))
            .andExpect(jsonPath("$.version").value(1))
        mvc.perform(get("/api/club/events").param("from", "2026-11-01").param("to", "2026-11-30").with(as_("나")))
            .andExpect(jsonPath("$[0].settlementTotal").value(50000))
    }

    @Test
    fun `하고 싶은 테마 모양 — 내 목록 · 묶음`() {
        mvc.perform(post("/api/me/wishes").with(as_("나")).contentType(MediaType.APPLICATION_JSON).content("""{"themeName":"목격자","themeKey":null}"""))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").isNumber)
            .andExpect(jsonPath("$.themeName").value("목격자"))
            .andExpect(jsonPath("$.storeName").isEmpty)
            .andExpect(jsonPath("$.createdAt").isString)
        mvc.perform(post("/api/me/wishes").with(as_("kakao:42")).contentType(MediaType.APPLICATION_JSON).content("""{"themeName":"목격자 "}"""))
        mvc.perform(get("/api/club/wishes").with(as_("나")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].themeName").value("목격자"))
            .andExpect(jsonPath("$[0].wanters[1].uid").value("kakao:42"))
            .andExpect(jsonPath("$[0].wanters[0].since").isString)
            .andExpect(jsonPath("$[0].playedBy").isArray)
    }
}
