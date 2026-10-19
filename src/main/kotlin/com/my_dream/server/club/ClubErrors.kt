package com.my_dream.server.club

import org.springframework.http.HttpStatus

/**
 * 동호회 API 가 **기계가 읽는 코드와 사람이 읽는 문구**로 거절하는 것.
 * `api/ClubErrorAdvice` 가 `{ "error": code, "message": message }` 로 바꾼다 (`WatchLimitDto` 와 같은 모양).
 *
 * **문구는 여기 한 곳에만 둔다.** 원본은 Floduler `작업명세서.md` §4.6 "에러 본문" 표이고,
 * 화면은 `message` 를 **그대로** 띄운다 — 여기를 고치면 그 표도 같이 고친다.
 *
 * `ResponseStatusException` 을 쓰지 않는 이유: 그 사유 문구는 클라이언트에 안 나간다
 * (`server.error.include-message` 기본값이 `never`). 2026-10-07 에 지난 회차 400 이 그래서
 * "불러오지 못했습니다" 로만 보였다 (작업명세서 ㉣).
 */
class ClubError(val status: HttpStatus, val code: String, override val message: String) : RuntimeException(message) {

    companion object {
        fun kakaoCodeInvalid() = ClubError(HttpStatus.BAD_REQUEST, "KAKAO_CODE_INVALID", "카카오 로그인이 만료됐어요. 다시 눌러 주세요.")
        fun redirectNotAllowed() = ClubError(HttpStatus.BAD_REQUEST, "REDIRECT_NOT_ALLOWED", "이 주소에서는 카카오 로그인을 쓸 수 없어요.")
        fun kakaoUnavailable() = ClubError(HttpStatus.SERVICE_UNAVAILABLE, "KAKAO_UNAVAILABLE", "카카오와 연결이 잘 안 돼요. 잠시 뒤 다시 눌러 주세요.")
        fun kakaoAlreadyLinked() = ClubError(HttpStatus.CONFLICT, "KAKAO_ALREADY_LINKED", "이미 다른 카카오 연결이 있어요. 다른 계정에 연결된 카카오인지 확인해 주세요.")
        fun badNickname() = ClubError(HttpStatus.BAD_REQUEST, "BAD_NICKNAME", "닉네임은 1자에서 20자 사이로 적어 주세요.")
        fun joinBlocked() = ClubError(HttpStatus.FORBIDDEN, "JOIN_BLOCKED", "이 계정으로는 가입할 수 없어요. 관리자에게 물어봐 주세요.")
        fun notAdmin() = ClubError(HttpStatus.FORBIDDEN, "NOT_ADMIN", "관리자만 할 수 있어요.")
        fun noSuchMember() = ClubError(HttpStatus.NOT_FOUND, "NO_SUCH_MEMBER", "그런 멤버를 찾을 수 없어요.")
        fun lastAdmin() = ClubError(HttpStatus.CONFLICT, "LAST_ADMIN", "마지막 관리자는 내보내거나 내릴 수 없어요. 다른 관리자를 먼저 지정해 주세요.")
        fun badState() = ClubError(HttpStatus.CONFLICT, "BAD_STATE", "지금 상태에서는 할 수 없어요. 목록을 새로 불러와 주세요.")
        fun notMember() = ClubError(HttpStatus.FORBIDDEN, "NOT_MEMBER", "동호회 승인을 받은 계정만 빈자리 알림을 걸 수 있어요.")
    }
}
