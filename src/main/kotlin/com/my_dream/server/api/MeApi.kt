package com.my_dream.server.api

import com.my_dream.server.club.ClubError
import com.my_dream.server.club.MeDto
import com.my_dream.server.club.MembershipService
import com.my_dream.server.club.ProfileInput
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice

/** [profile] 은 선택이다 — 옛 앱은 닉네임만 보낸다 (§4.11) */
data class JoinRequest(val nickname: String = "", val profile: ProfileInput? = null)

data class ProfileRequest(val nickname: String = "", val profile: ProfileInput? = null)

/**
 * 내 동호회 상태 · 가입 신청 · 프로필 고치기. 계약은 Floduler `작업명세서.md` §4.6 · §4.11.
 *
 * 화면은 로그인하자마자 `GET /api/me` 로 상태를 받는다. `NONE` 이면 닉네임(과 프로필)을 받아 `join` 한다.
 * 이 경로가 404 면 서버가 아직 동호회를 모르는 것이라, 화면은 동호회 기능만 숨긴다.
 */
@RestController
@RequestMapping("/api/me")
class MeController(private val membership: MembershipService) {

    @GetMapping
    fun me(@AuthenticationPrincipal jwt: Jwt): MeDto = membership.me(jwt.uid())

    @PostMapping("/join")
    fun join(@AuthenticationPrincipal jwt: Jwt, @RequestBody request: JoinRequest): MeDto =
        membership.join(jwt.uid(), request.nickname, request.profile)

    @PutMapping("/profile")
    fun updateProfile(@AuthenticationPrincipal jwt: Jwt, @RequestBody request: ProfileRequest): MeDto =
        membership.updateProfile(jwt.uid(), request.nickname, request.profile)
}

/**
 * 본문이 JSON 으로 안 읽히면 `BAD_PROFILE`. 이 컨트롤러의 본문에서 타입이 틀릴 수 있는 칸은 사실상 프로필뿐이다
 * (`lifeTheme` 에 문자열, `realName` 에 객체 같은 것) — 스프링 기본 400 은 `{ error, message }` 가 아니라 화면이 못 읽는다.
 * 이 컨트롤러에만 건다 (`ClubEventInputAdvice` 와 같은 이유)
 */
@RestControllerAdvice(assignableTypes = [MeController::class])
class MeInputAdvice(private val errors: ClubErrorAdvice) {

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun onUnreadable(e: HttpMessageNotReadableException): ResponseEntity<Map<String, Any?>> = errors.onClubError(ClubError.badProfile())
}
