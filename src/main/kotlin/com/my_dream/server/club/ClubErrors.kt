package com.my_dream.server.club

import org.springframework.http.HttpStatus

/**
 * 동호회 API 가 **기계가 읽는 코드와 사람이 읽는 문구**로 거절하는 것.
 * `api/ClubErrorAdvice` 가 `{ "error": code, "message": message }` 로 바꾼다 (`WatchLimitDto` 와 같은 모양).
 *
 * **문구는 여기 한 곳에만 둔다.** 원본은 Floduler `작업명세서.md` §4.6 "에러 본문" 표이고,
 * 화면은 `message` 를 **그대로** 띄운다 — 여기를 고치면 그 표도 같이 고친다.
 *
 * 화면에 보이는 말은 **"운영진"** 이다(2026-10-19 사용자 결정). 코드 이름(`NOT_ADMIN`·`LAST_ADMIN`)과
 * 역할 값(`ADMIN`)은 계약이라 그대로 두고 **사람이 읽는 문구만** 바꿨다.
 *
 * `ResponseStatusException` 을 쓰지 않는 이유: 그 사유 문구는 클라이언트에 안 나간다
 * (`server.error.include-message` 기본값이 `never`). 2026-10-07 에 지난 회차 400 이 그래서
 * "불러오지 못했습니다" 로만 보였다 (작업명세서 ㉣).
 */
class ClubError(
    val status: HttpStatus,
    val code: String,
    override val message: String,
    /** 본문에 같이 실을 것. `EVENT_CONFLICT` 가 최신 일정을 `event` 로 싣는다 — 화면이 "새로 불러올까요?" 를 바로 그리게 */
    val extra: Map<String, Any?> = emptyMap(),
) : RuntimeException(message) {

    companion object {
        fun kakaoCodeInvalid() = ClubError(HttpStatus.BAD_REQUEST, "KAKAO_CODE_INVALID", "카카오 로그인이 만료됐어요. 다시 눌러 주세요.")
        fun redirectNotAllowed() = ClubError(HttpStatus.BAD_REQUEST, "REDIRECT_NOT_ALLOWED", "이 주소에서는 카카오 로그인을 쓸 수 없어요.")
        fun kakaoUnavailable() = ClubError(HttpStatus.SERVICE_UNAVAILABLE, "KAKAO_UNAVAILABLE", "카카오와 연결이 잘 안 돼요. 잠시 뒤 다시 눌러 주세요.")
        fun kakaoAlreadyLinked() = ClubError(HttpStatus.CONFLICT, "KAKAO_ALREADY_LINKED", "이미 다른 카카오 연결이 있어요. 다른 계정에 연결된 카카오인지 확인해 주세요.")
        fun badNickname() = ClubError(HttpStatus.BAD_REQUEST, "BAD_NICKNAME", "닉네임은 1자에서 20자 사이로 적어 주세요.")
        fun joinBlocked() = ClubError(HttpStatus.FORBIDDEN, "JOIN_BLOCKED", "이 계정으로는 가입할 수 없어요. 운영진에게 물어봐 주세요.")
        fun notAdmin() = ClubError(HttpStatus.FORBIDDEN, "NOT_ADMIN", "운영진만 할 수 있어요.")
        fun noSuchMember() = ClubError(HttpStatus.NOT_FOUND, "NO_SUCH_MEMBER", "그런 멤버를 찾을 수 없어요.")
        fun lastAdmin() = ClubError(HttpStatus.CONFLICT, "LAST_ADMIN", "마지막 운영진은 내보내거나 내릴 수 없어요. 다른 운영진을 먼저 지정해 주세요.")
        fun badState() = ClubError(HttpStatus.CONFLICT, "BAD_STATE", "지금 상태에서는 할 수 없어요. 목록을 새로 불러와 주세요.")
        fun notMember() = ClubError(HttpStatus.FORBIDDEN, "NOT_MEMBER", "동호회 승인을 받은 계정만 빈자리 알림을 걸 수 있어요.")

        // --- 2단계 일정 (Floduler §4.7) ---
        fun notClubMember() = ClubError(HttpStatus.FORBIDDEN, "NOT_CLUB_MEMBER", "동호회 승인을 받은 계정만 일정을 볼 수 있어요.")
        fun noSuchEvent() = ClubError(HttpStatus.NOT_FOUND, "NO_SUCH_EVENT", "일정을 찾을 수 없어요. 지워졌을 수 있어요.")
        fun notEventOwner() = ClubError(HttpStatus.FORBIDDEN, "NOT_EVENT_OWNER", "일정을 만든 사람이나 운영진만 할 수 있어요.")
        fun notParticipant() = ClubError(HttpStatus.FORBIDDEN, "NOT_PARTICIPANT", "참여한 사람, 만든 사람, 운영진만 예약 상태를 바꿀 수 있어요.")
        fun eventFull() = ClubError(HttpStatus.CONFLICT, "EVENT_FULL", "자리가 다 찼어요.")
        fun eventConflict(latest: Any) =
            ClubError(HttpStatus.CONFLICT, "EVENT_CONFLICT", "다른 사람이 먼저 고쳤어요. 새로 불러온 내용을 확인해 주세요.", mapOf("event" to latest))
        fun capacityTooSmall() = ClubError(HttpStatus.CONFLICT, "CAPACITY_TOO_SMALL", "지금 참여한 사람보다 적게 줄일 수 없어요.")
        fun badEvent() = ClubError(HttpStatus.BAD_REQUEST, "BAD_EVENT", "일정 내용을 다시 확인해 주세요.")
        fun badRange() = ClubError(HttpStatus.BAD_REQUEST, "BAD_RANGE", "불러올 날짜 범위를 다시 확인해 주세요. 한 번에 62일까지예요.")

        // --- 3단계 시간 협의 (§4.8) ---
        fun badAvailability() = ClubError(HttpStatus.BAD_REQUEST, "BAD_AVAILABILITY", "되는 시간을 다시 확인해 주세요.")
        fun notACandidate() = ClubError(HttpStatus.BAD_REQUEST, "NOT_A_CANDIDATE", "후보 날짜가 아니에요. 새로 불러와 주세요.")
        fun notParticipantAnswer() = ClubError(HttpStatus.FORBIDDEN, "NOT_PARTICIPANT_ANSWER", "참여한 사람만 되는 날짜를 고를 수 있어요.")

        // --- 4단계 해 본 테마 (§4.9) ---
        fun badPlayed() = ClubError(HttpStatus.BAD_REQUEST, "BAD_PLAYED", "테마 이름을 확인해 주세요.")
        fun noSuchPlayed() = ClubError(HttpStatus.NOT_FOUND, "NO_SUCH_PLAYED", "그 기록을 찾을 수 없어요.")

        // --- 5단계 알림 (§4.10) ---
        fun tooSoon() = ClubError(HttpStatus.TOO_MANY_REQUESTS, "TOO_SOON", "1분 뒤에 다시 보내 주세요.")
    }
}
