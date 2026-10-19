package com.my_dream.server.api

import com.my_dream.server.domain.ClubMember
import com.my_dream.server.domain.ClubMemberRepository
import com.my_dream.server.domain.MemberRole
import com.my_dream.server.domain.MemberStatus
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.hamcrest.Matchers.containsString
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant

/**
 * 동호회 API 의 **문** — 경로마다 로그인이 필요한지, 관리자 문이 닫혀 있는지, 에러 본문이 계약 모양인지.
 *
 * 서비스 테스트는 "판단이 맞는가" 를 보고, 여기는 **"그 판단까지 요청이 가는가"** 를 본다.
 * `ApiSecurityConfig` 끝이 `anyRequest().permitAll()` 이라 새 경로를 적는 걸 잊으면 **조용히 열린다** —
 * 그걸 잡는 자리가 여기다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ClubApiSecurityTest @Autowired constructor(
    private val mvc: MockMvc,
    private val members: ClubMemberRepository,
) {

    @BeforeEach
    fun setUp() {
        members.deleteAll()
        members.save(ClubMember("방장", "방장", MemberRole.ADMIN, MemberStatus.APPROVED, Instant.now()))
    }

    private fun as_(uid: String) = jwt().jwt { it.subject(uid) }

    @Test
    fun `토큰 없이는 내 정보도 관리자 화면도 401`() {
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized)
        mvc.perform(post("/api/me/join").contentType(MediaType.APPLICATION_JSON).content("""{"nickname":"철수"}"""))
            .andExpect(status().isUnauthorized)
        mvc.perform(get("/api/admin/members").param("status", "PENDING")).andExpect(status().isUnauthorized)
        mvc.perform(post("/api/admin/members/u1/approve")).andExpect(status().isUnauthorized)
        mvc.perform(post("/api/auth/kakao/link").contentType(MediaType.APPLICATION_JSON).content("""{"code":"c","redirectUri":"r"}"""))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `로그인하면 가입 신청이 된다`() {
        mvc.perform(get("/api/me").with(as_("u1")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.uid").value("u1"))
            .andExpect(jsonPath("$.status").value("NONE"))
            // 계약은 `string|null` — **키는 있고 값이 null** 이다. 키가 빠지면 화면의 타입이 거짓말을 한다
            .andExpect(content().string(containsString("\"nickname\":null")))
            .andExpect(content().string(containsString("\"role\":null")))

        mvc.perform(post("/api/me/join").with(as_("u1")).contentType(MediaType.APPLICATION_JSON).content("""{"nickname":" 철수 "}"""))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.nickname").value("철수"))
    }

    @Test
    fun `에러 본문은 error 와 message 다`() {
        // 화면은 error 로 가르고 message 를 그대로 띄운다 (§4.6). 스프링 기본 에러 본문이 나가면 둘 다 없다
        mvc.perform(post("/api/me/join").with(as_("u1")).contentType(MediaType.APPLICATION_JSON).content("""{"nickname":""}"""))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("BAD_NICKNAME"))
            .andExpect(jsonPath("$.message").value("닉네임은 1자에서 20자 사이로 적어 주세요."))
    }

    @Test
    fun `일반 멤버는 관리자 화면에 못 들어간다`() {
        members.save(ClubMember("u2", "멤버", MemberRole.MEMBER, MemberStatus.APPROVED, Instant.now()))
        mvc.perform(get("/api/admin/members").param("status", "PENDING").with(as_("u2")))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error").value("NOT_ADMIN"))
            // 화면이 그대로 띄우는 문구. 원본은 Floduler 작업명세서 §4.6 — 화면 용어는 "운영진"
            .andExpect(jsonPath("$.message").value("운영진만 할 수 있어요."))
    }

    @Test
    fun `관리자는 목록을 보고 승인한다`() {
        members.save(ClubMember("kakao:42", "철수", MemberRole.MEMBER, MemberStatus.PENDING, Instant.now()))

        mvc.perform(get("/api/admin/members").param("status", "PENDING").with(as_("방장")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].uid").value("kakao:42"))
            .andExpect(jsonPath("$[0].hasDevice").value(false))

        // 카카오 uid 에는 `:` 가 들어 있다. 경로에서 잘리지 않아야 한다
        mvc.perform(post("/api/admin/members/kakao:42/approve").with(as_("방장")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.uid").value("kakao:42"))
            .andExpect(jsonPath("$.status").value("APPROVED"))
            .andExpect(jsonPath("$.decidedAt").exists())
    }

    @Test
    fun `역할 바꾸기와 마지막 관리자`() {
        mvc.perform(post("/api/admin/members/방장/role").with(as_("방장")).contentType(MediaType.APPLICATION_JSON).content("""{"role":"MEMBER"}"""))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("LAST_ADMIN"))
            .andExpect(jsonPath("$.message").value("마지막 운영진은 내보내거나 내릴 수 없어요. 다른 운영진을 먼저 지정해 주세요."))
    }

    @Test
    fun `카카오 로그인 경로는 토큰 없이 열려 있다`() {
        // 로그인하려고 부르는 경로라 로그인을 요구할 수 없다. 401 이 아니면 문은 열린 것이다
        // (여기서는 허용 안 된 주소라 400 이 온다 — 카카오까지 안 나간다)
        mvc.perform(post("/api/auth/kakao").contentType(MediaType.APPLICATION_JSON).content("""{"code":"c","redirectUri":"https://evil.test/"}"""))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("REDIRECT_NOT_ALLOWED"))
    }

    @Test
    fun `승인 안 된 사람의 빈자리 알림은 403 NOT_MEMBER 본문으로`() {
        mvc.perform(post("/api/watches").with(as_("u1")).contentType(MediaType.APPLICATION_JSON).content("""{"slotId":1}"""))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error").value("NOT_MEMBER"))
            .andExpect(jsonPath("$.message").value("동호회 승인을 받은 계정만 빈자리 알림을 걸 수 있어요."))
    }

    @Test
    fun `프리플라이트가 POST 와 Authorization 을 허락한다`() {
        // 브라우저는 Authorization 을 실은 POST 앞에 OPTIONS 를 먼저 보낸다. 여기서 막히면 본 요청은 아예 안 나간다
        mvc.perform(
            options("/api/me/join")
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "authorization,content-type"),
        )
            .andExpect(status().isOk)
            .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
    }
}
