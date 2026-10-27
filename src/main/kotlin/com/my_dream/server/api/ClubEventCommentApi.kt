package com.my_dream.server.api

import com.my_dream.server.club.ClubError
import com.my_dream.server.club.DeletedDto
import com.my_dream.server.club.EventCommentDto
import com.my_dream.server.club.EventCommentInput
import com.my_dream.server.club.EventCommentService
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
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

/**
 * 일정 댓글 (D49). 계약은 Floduler `작업명세서.md` §4.16.
 *
 * [ClubEventController] 와 컨트롤러를 나눈 이유는 **오류 모양**이다 — 그쪽은 깨진 본문을 `BAD_EVENT` 로 답하는데,
 * 댓글 칸이 깨졌을 때 "일정 내용을 확인해 주세요" 가 뜨면 사람이 엉뚱한 칸을 본다
 */
@RestController
@RequestMapping("/api/club/events")
class ClubEventCommentController(private val comments: EventCommentService) {

    @GetMapping("/{id}/comments")
    fun list(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: Long): List<EventCommentDto> = comments.list(jwt.uid(), id)

    @PostMapping("/{id}/comments")
    fun create(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: Long, @RequestBody input: EventCommentInput): EventCommentDto =
        comments.create(jwt.uid(), id, input)

    /** `/events/comments/7` — `/events/{id}` 와 칸 수가 달라 겹치지 않는다 */
    @DeleteMapping("/comments/{commentId}")
    fun delete(@AuthenticationPrincipal jwt: Jwt, @PathVariable commentId: Long): DeletedDto = comments.delete(jwt.uid(), commentId)
}

/** 깨진 본문 · 숫자가 아닌 id 도 `{ error, message }` 로. 이 컨트롤러에만 건다 (`ClubFeedbackInputAdvice` 와 같은 이유) */
@RestControllerAdvice(assignableTypes = [ClubEventCommentController::class])
class ClubEventCommentInputAdvice(private val errors: ClubErrorAdvice) {

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun onUnreadable(e: HttpMessageNotReadableException): ResponseEntity<Map<String, Any?>> = errors.onClubError(ClubError.badEventComment())

    /** `/events/abc/comments` 는 그런 일정이 없고, `/events/comments/abc` 는 그런 댓글이 없다 */
    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun onBadId(e: MethodArgumentTypeMismatchException): ResponseEntity<Map<String, Any?>> =
        errors.onClubError(if (e.name == "commentId") ClubError.noSuchComment() else ClubError.noSuchEvent())
}
