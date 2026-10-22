package com.my_dream.server.api

import com.my_dream.server.club.ClubError
import com.my_dream.server.club.NoticeDeletedDto
import com.my_dream.server.club.NoticeDto
import com.my_dream.server.club.NoticeInput
import com.my_dream.server.club.NoticeService
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice

/** 홈 공지 (D46). 계약은 Floduler `작업명세서.md` §4.14 */
@RestController
@RequestMapping("/api/club/notice")
class ClubNoticeController(private val notices: NoticeService) {

    /**
     * 없으면 본문이 **`null` 네 글자**다. 컨트롤러가 null 을 돌려주면 스프링은 본문을 **비워서** 보내고,
     * 화면의 `res.json()` 은 빈 본문에서 터진다 — 계약은 `NoticeDto | null` 이다
     */
    @GetMapping
    fun current(@AuthenticationPrincipal jwt: Jwt): ResponseEntity<Any> {
        val notice = notices.current(jwt.uid())
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(notice ?: "null")
    }

    @PutMapping
    fun put(@AuthenticationPrincipal jwt: Jwt, @RequestBody input: NoticeInput): NoticeDto = notices.put(jwt.uid(), input)

    @DeleteMapping
    fun delete(@AuthenticationPrincipal jwt: Jwt): NoticeDeletedDto = notices.delete(jwt.uid())
}

/** 깨진 본문도 `{ error, message }` 로. 이 컨트롤러에만 건다 (`ClubWishInputAdvice` 와 같은 이유) */
@RestControllerAdvice(assignableTypes = [ClubNoticeController::class])
class ClubNoticeInputAdvice(private val errors: ClubErrorAdvice) {

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun onUnreadable(e: HttpMessageNotReadableException): ResponseEntity<Map<String, Any?>> = errors.onClubError(ClubError.badNotice())
}
