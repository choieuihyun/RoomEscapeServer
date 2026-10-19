package com.my_dream.server.api

import com.my_dream.server.club.AvailabilityInput
import com.my_dream.server.club.ClubError
import com.my_dream.server.club.ClubEventService
import com.my_dream.server.club.ClubMemberDto
import com.my_dream.server.club.DeletedDto
import com.my_dream.server.club.EventDto
import com.my_dream.server.club.EventInput
import jakarta.servlet.http.HttpServletRequest
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

data class UidRequest(val uid: String = "")

data class BookingRequest(val booked: Boolean? = null)

/**
 * 동호회 일정 (아키텍처 D30 · D31 · D32). 계약은 Floduler `작업명세서.md` §4.7 · §4.8.
 *
 * 컨트롤러는 얇다 — 승인 멤버인지, 관리할 수 있는지는 전부 [ClubEventService] 가 DB 로 본다.
 * 수정도 POST 다. CORS 허용 메서드가 GET·POST·DELETE 뿐이라 PUT·PATCH 를 쓰려면 CORS 를 넓혀야 한다.
 */
@RestController
@RequestMapping("/api/club")
class ClubEventController(private val service: ClubEventService) {

    /** 날짜를 문자열로 받는다 — `LocalDate` 로 받으면 형식이 틀렸을 때 스프링 기본 400 이 나가서 계약의 `BAD_RANGE` 가 안 된다 */
    @GetMapping("/events")
    fun list(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
    ): List<EventDto> = service.list(jwt.uid(), from, to)

    @GetMapping("/events/{id}")
    fun get(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: Long): EventDto = service.get(jwt.uid(), id)

    @PostMapping("/events")
    fun create(@AuthenticationPrincipal jwt: Jwt, @RequestBody input: EventInput): EventDto = service.create(jwt.uid(), input)

    @PostMapping("/events/{id}")
    fun update(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: Long, @RequestBody input: EventInput): EventDto =
        service.update(jwt.uid(), id, input)

    @DeleteMapping("/events/{id}")
    fun delete(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: Long): DeletedDto = service.delete(jwt.uid(), id)

    @PostMapping("/events/{id}/join")
    fun join(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: Long): EventDto = service.join(jwt.uid(), id)

    @PostMapping("/events/{id}/leave")
    fun leave(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: Long): EventDto = service.leave(jwt.uid(), id)

    @PostMapping("/events/{id}/participants")
    fun addParticipant(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: Long, @RequestBody req: UidRequest): EventDto =
        service.addParticipant(jwt.uid(), id, req.uid.trim())

    /** [uid] 에 `kakao:42` 처럼 `:` 가 들어간다. 경로 변수 그대로 받는다 (1단계 관리자 API 와 같다) */
    @DeleteMapping("/events/{id}/participants/{uid}")
    fun removeParticipant(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: Long, @PathVariable uid: String): EventDto =
        service.removeParticipant(jwt.uid(), id, uid)

    @PostMapping("/events/{id}/booking")
    fun booking(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: Long, @RequestBody req: BookingRequest): EventDto =
        service.setBooking(jwt.uid(), id, req.booked ?: throw ClubError.badEvent())

    @PostMapping("/events/{id}/availability")
    fun answer(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: Long, @RequestBody input: AvailabilityInput): EventDto =
        service.answer(jwt.uid(), id, input)

    /** 날짜도 문자열로 받는다 — 형식이 틀리면 계약의 `BAD_AVAILABILITY` 여야 한다 */
    @DeleteMapping("/events/{id}/availability/{date}")
    fun clearAnswer(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: Long, @PathVariable date: String): EventDto =
        service.clearAnswer(jwt.uid(), id, date)

    @GetMapping("/members")
    fun members(@AuthenticationPrincipal jwt: Jwt): List<ClubMemberDto> = service.members(jwt.uid())
}

/**
 * 일정 API 의 **틀린 입력**도 계약 모양(`{ error, message }`)으로 답한다.
 *
 * **이 컨트롤러에만** 건다(`assignableTypes`). 전역으로 걸면 이미 배포된 `/api/watches` · `/api/devices` 의
 * 400 모양까지 바뀐다 — 그쪽 화면은 지금 모양에 맞춰져 있다.
 */
@RestControllerAdvice(assignableTypes = [ClubEventController::class])
class ClubEventInputAdvice(private val errors: ClubErrorAdvice) {

    /**
     * JSON 이 깨졌거나 타입이 틀렸다 (`"booked": "아마도"`). 응답 경로는 `BAD_AVAILABILITY` 다 —
     * 화면이 "일정 내용을 확인해 주세요" 를 보여 주면 사람이 엉뚱한 칸을 고친다
     */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun onUnreadable(e: HttpMessageNotReadableException, req: HttpServletRequest): ResponseEntity<Map<String, Any?>> =
        errors.onClubError(if (req.requestURI.endsWith("/availability")) ClubError.badAvailability() else ClubError.badEvent())

    /** `/events/abc` — 그런 일정은 없다 */
    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun onBadId(e: MethodArgumentTypeMismatchException): ResponseEntity<Map<String, Any?>> = errors.onClubError(ClubError.noSuchEvent())
}
