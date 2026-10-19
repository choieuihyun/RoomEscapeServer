package com.my_dream.server.api

import com.my_dream.server.club.ClubError
import com.my_dream.server.club.ClubEventService
import com.my_dream.server.club.DeletedDto
import com.my_dream.server.club.MyEventsDto
import com.my_dream.server.club.PlayedDto
import com.my_dream.server.club.PlayedInput
import com.my_dream.server.club.PlayedService
import com.my_dream.server.club.PushTestDto
import com.my_dream.server.club.PushTestService
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

/**
 * "내 정보" 화면 — 해 본 테마 · 지난 동호회 일정 (아키텍처 D33). 계약은 Floduler `작업명세서.md` §4.9.
 *
 * `/api/me` 를 맡은 [MeController] 와 **컨트롤러를 나눈다.** 아래 입력 오류 처리를 거기에 걸면
 * 이미 배포된 `/api/me` 의 오류 모양까지 바뀐다.
 */
@RestController
@RequestMapping("/api")
class ClubProfileController(
    private val played: PlayedService,
    private val events: ClubEventService,
    private val pushTest: PushTestService,
) {

    @GetMapping("/me/played")
    fun mine(@AuthenticationPrincipal jwt: Jwt): List<PlayedDto> = played.mine(jwt.uid())

    @PostMapping("/me/played")
    fun add(@AuthenticationPrincipal jwt: Jwt, @RequestBody input: PlayedInput): PlayedDto = played.add(jwt.uid(), input)

    @DeleteMapping("/me/played/{id}")
    fun delete(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: Long): DeletedDto = played.delete(jwt.uid(), id)

    @GetMapping("/club/members/{uid}/played")
    fun ofMember(@AuthenticationPrincipal jwt: Jwt, @PathVariable uid: String): List<PlayedDto> = played.ofMember(jwt.uid(), uid)

    /** 이 기기들로 알림이 오는지 (§4.10). 로그인만 — 동호회 자격은 필요 없다. 1분에 한 번(`429 TOO_SOON`) */
    @PostMapping("/me/push-test")
    fun pushTest(@AuthenticationPrincipal jwt: Jwt): PushTestDto = pushTest.send(jwt.uid())

    /** 문자열로 받는다 — 형식이 틀리면 스프링 기본 400 이 아니라 계약의 `BAD_RANGE` 여야 한다 */
    @GetMapping("/me/events")
    fun myPast(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestParam(required = false) before: String?,
        @RequestParam(required = false) beforeId: String?,
        @RequestParam(required = false) limit: String?,
    ): MyEventsDto = events.myPast(jwt.uid(), before, beforeId, limit)
}

/** 이 컨트롤러의 **틀린 입력**도 `{ error, message }` 로. 이 컨트롤러에만 건다 (`ClubEventInputAdvice` 와 같은 이유) */
@RestControllerAdvice(assignableTypes = [ClubProfileController::class])
class ClubProfileInputAdvice(private val errors: ClubErrorAdvice) {

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun onUnreadable(e: HttpMessageNotReadableException): ResponseEntity<Map<String, Any?>> = errors.onClubError(ClubError.badPlayed())

    /** `/me/played/abc` — 그런 기록은 없다 */
    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun onBadId(e: MethodArgumentTypeMismatchException): ResponseEntity<Map<String, Any?>> = errors.onClubError(ClubError.noSuchPlayed())
}
