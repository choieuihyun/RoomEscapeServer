package com.my_dream.server.api

import com.my_dream.server.club.ClubError
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * [ClubError] 를 `{ error, message }` 로 바꾼다. **어느 컨트롤러에서 던지든** 같은 모양이 나가게
 * 컨트롤러 안의 `@ExceptionHandler`(감시 한도가 그렇다) 대신 전역으로 둔다 —
 * 빈자리 알림(`WatchService`)도 `NOT_MEMBER` 를 이걸로 던진다.
 */
@RestControllerAdvice
class ClubErrorAdvice {

    /** 본문은 `{ error, message }` 에 [ClubError.extra] 를 덧붙인 것이다 (`EVENT_CONFLICT` 의 `event`) */
    @ExceptionHandler(ClubError::class)
    fun onClubError(e: ClubError): ResponseEntity<Map<String, Any?>> =
        ResponseEntity.status(e.status).body(linkedMapOf<String, Any?>("error" to e.code, "message" to e.message) + e.extra)
}
