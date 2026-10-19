package com.my_dream.server.api

import com.my_dream.server.club.ClubError
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/** 기계가 읽는 거절. 모양은 `WatchLimitDto` 와 같다 — Floduler `작업명세서.md` §4.6 "에러 본문" */
data class ClubErrorDto(val error: String, val message: String)

/**
 * [ClubError] 를 `{ error, message }` 로 바꾼다. **어느 컨트롤러에서 던지든** 같은 모양이 나가게
 * 컨트롤러 안의 `@ExceptionHandler`(감시 한도가 그렇다) 대신 전역으로 둔다 —
 * 빈자리 알림(`WatchService`)도 `NOT_MEMBER` 를 이걸로 던진다.
 */
@RestControllerAdvice
class ClubErrorAdvice {

    @ExceptionHandler(ClubError::class)
    fun onClubError(e: ClubError): ResponseEntity<ClubErrorDto> =
        ResponseEntity.status(e.status).body(ClubErrorDto(e.code, e.message))
}
