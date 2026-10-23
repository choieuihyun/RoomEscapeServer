package com.my_dream.server.api

import com.my_dream.server.club.ClubError
import com.my_dream.server.club.DeletedDto
import com.my_dream.server.club.FeedbackCommentDto
import com.my_dream.server.club.FeedbackDto
import com.my_dream.server.club.FeedbackLikeInput
import com.my_dream.server.club.FeedbackService
import com.my_dream.server.club.FeedbackStatusInput
import com.my_dream.server.club.FeedbackTextInput
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
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

/** 의견 게시판 (D47). 계약은 Floduler `작업명세서.md` §4.15 */
@RestController
@RequestMapping("/api/club/feedback")
class ClubFeedbackController(private val feedback: FeedbackService) {

    @GetMapping
    fun list(@AuthenticationPrincipal jwt: Jwt): List<FeedbackDto> = feedback.list(jwt.uid())

    @PostMapping
    fun create(@AuthenticationPrincipal jwt: Jwt, @RequestBody input: FeedbackTextInput): FeedbackDto = feedback.create(jwt.uid(), input)

    @PutMapping("/{id}")
    fun edit(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: Long, @RequestBody input: FeedbackTextInput): FeedbackDto =
        feedback.edit(jwt.uid(), id, input)

    @DeleteMapping("/{id}")
    fun delete(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: Long): DeletedDto = feedback.delete(jwt.uid(), id)

    @PostMapping("/{id}/like")
    fun like(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: Long, @RequestBody input: FeedbackLikeInput): FeedbackDto =
        feedback.like(jwt.uid(), id, input)

    @PutMapping("/{id}/status")
    fun status(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: Long, @RequestBody input: FeedbackStatusInput): FeedbackDto =
        feedback.status(jwt.uid(), id, input)

    @GetMapping("/{id}/comments")
    fun comments(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: Long): List<FeedbackCommentDto> = feedback.comments(jwt.uid(), id)

    @PostMapping("/{id}/comments")
    fun comment(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: Long, @RequestBody input: FeedbackTextInput): FeedbackCommentDto =
        feedback.comment(jwt.uid(), id, input)

    @DeleteMapping("/comments/{commentId}")
    fun deleteComment(@AuthenticationPrincipal jwt: Jwt, @PathVariable commentId: Long): DeletedDto = feedback.deleteComment(jwt.uid(), commentId)
}

/** 깨진 본문 · 숫자가 아닌 id 도 `{ error, message }` 로. 이 컨트롤러에만 건다 (`ClubWishInputAdvice` 와 같은 이유) */
@RestControllerAdvice(assignableTypes = [ClubFeedbackController::class])
class ClubFeedbackInputAdvice(private val errors: ClubErrorAdvice) {

    /** 경로로 가른다 — 댓글 칸이 깨졌는데 "의견은 1~1000자" 가 뜨면 엉뚱한 칸을 본다 */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun onUnreadable(e: HttpMessageNotReadableException, req: HttpServletRequest): ResponseEntity<Map<String, Any?>> =
        errors.onClubError(
            when {
                req.requestURI.endsWith("/comments") -> ClubError.badComment()
                req.requestURI.endsWith("/status") -> ClubError.badStatus()
                else -> ClubError.badFeedback()
            },
        )

    /** `/feedback/abc` — 그런 글은 없다 */
    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun onBadId(e: MethodArgumentTypeMismatchException): ResponseEntity<Map<String, Any?>> =
        errors.onClubError(if (e.name == "commentId") ClubError.noSuchComment() else ClubError.noSuchFeedback())
}
