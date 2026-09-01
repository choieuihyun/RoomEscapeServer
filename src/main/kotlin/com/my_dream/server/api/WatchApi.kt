package com.my_dream.server.api

import com.my_dream.server.domain.TimeSlotRepository
import com.my_dream.server.domain.Watch
import com.my_dream.server.domain.WatchRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/** 감시를 걸 자리. [slotId] 는 `GET /api/schedule` 이 회차마다 내려준 `id` 다. */
data class WatchRequest(val slotId: Long)

/**
 * 감시 목록 + **한도**.
 *
 * **한도를 같이 내려 주는 이유:** 화면이 `2 / 3` 을 그리고 버튼을 미리 막으려면 한도를 알아야 하는데,
 * 프론트에 `3` 을 박아 두면 서버에서 바꿨을 때 **두 값이 조용히 갈라진다.**
 * 같은 숫자를 두 군데 두면 하나는 거짓말을 시작한다 — 이 프로젝트가 반복해 겪은 것이다.
 *
 * [watches] 의 길이가 곧 지금 쓴 개수다. 지난 회차는 목록에도 없고 한도에도 안 센다.
 */
data class WatchListDto(val limit: Int, val watches: List<WatchDto>)

/** 한도를 넘겨 등록하려 했을 때. 프론트가 **문구가 아니라 `error` 코드로** 알아본다 */
data class WatchLimitDto(val error: String, val limit: Int, val message: String)

/**
 * 내가 걸어 둔 감시 하나.
 * 자리를 다시 조회하지 않아도 화면에 그릴 수 있게 매장·테마까지 펴서 준다.
 */
data class WatchDto(
    val id: Long,
    val branch: String,
    val theme: String,
    val date: LocalDate,
    /** 자정부터의 분. 조회 API 의 `session.t` 와 같은 형식이다 */
    val t: Int,
    /** 지금 예약 가능한 상태인지. 이미 풀렸는데 감시가 남아 있을 수 있다 */
    val available: Boolean,
    val createdAt: Instant,
)

/**
 * 감시 신청 — "이 자리가 풀리면 알려줘".
 *
 * **이 경로만 로그인이 필요하다.** 조회 API 는 지금까지처럼 공개다
 * (시큐리티 설정은 `ApiSecurityConfig`). 신원은 Firebase 토큰의 `sub` 다.
 */
@RestController
@RequestMapping("/api/watches")
class WatchController(private val service: WatchService) {

    @GetMapping
    fun list(@AuthenticationPrincipal jwt: Jwt): WatchListDto = service.list(jwt.uid())

    /**
     * 한도를 넘겼을 때 **409 + 기계가 읽는 본문**을 준다.
     *
     * `ResponseStatusException` 의 사유 문구는 클라이언트에 안 나간다
     * (`server.error.include-message` 기본값이 `never`). 그대로 두면 프론트가
     * **"한도 초과" 와 다른 409 를 구분할 수 없다.**
     */
    @ExceptionHandler(WatchLimitExceeded::class)
    fun onLimit(e: WatchLimitExceeded): ResponseEntity<WatchLimitDto> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(
            WatchLimitDto(
                error = "WATCH_LIMIT_EXCEEDED",
                limit = e.limit,
                message = "감시는 최대 ${e.limit}개까지 걸 수 있습니다. 하나를 지우고 다시 시도해 주세요.",
            ),
        )

    @PostMapping
    fun register(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestBody request: WatchRequest,
    ): ResponseEntity<WatchDto> =
        ResponseEntity.status(HttpStatus.CREATED).body(service.register(jwt.uid(), request.slotId))

    @DeleteMapping("/{id}")
    fun cancel(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: Long): ResponseEntity<Void> {
        service.cancel(jwt.uid(), id)
        return ResponseEntity.noContent().build()
    }

    /**
     * Firebase uid. 서명 검증을 통과한 토큰에는 항상 `sub` 가 있지만,
     * 타입상 null 이 가능하니 여기서 한 번만 확인하고 아래로는 `String` 으로 넘긴다.
     */
    private fun Jwt.uid(): String =
        subject ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "토큰에 sub 가 없습니다")
}

/** 한도 초과. [WatchController.onLimit] 이 409 로 바꾼다 */
class WatchLimitExceeded(val limit: Int) : RuntimeException()

@Service
class WatchService(
    private val watches: WatchRepository,
    private val slots: TimeSlotRepository,
    /**
     * 한 사람이 걸 수 있는 감시 수.
     *
     * **크롤 부하 때문이 아니다.** 감시는 매장 사이트에 요청을 한 건도 더 보내지 않는다
     * (아키텍처 D3 — 수집기는 감시를 아예 모르고 매 바퀴 전량을 긁는다).
     * 여기서 막는 것은 **우리 DB** 다 — 감시 하나가 전이마다 쿨다운 조회 한 번을 만들고,
     * 그 조회가 아직 N+1 이다 (작업명세서 M5).
     *
     * 그래서 이 값은 **성능이 나아지면 올릴 수 있는 값**이지 안전 장치가 아니다.
     * 환경변수로 뺀 이유가 그것이다.
     */
    @param:Value("\${watch.max-per-user:3}") private val maxPerUser: Int,
) {

    @Transactional(readOnly = true)
    fun list(userId: String): WatchListDto =
        WatchListDto(maxPerUser, watches.findActiveByUserId(userId, LocalDate.now(), LocalTime.now()).map { it.toDto() })

    @Transactional
    fun register(userId: String, slotId: Long): WatchDto {
        val slot = slots.findById(slotId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "그런 회차가 없습니다")
        }
        // 지난 자리를 지켜보겠다는 건 성립하지 않는다. 조용히 받아 두면 영영 안 울리는 감시가 쌓인다
        if (slot.date < LocalDate.now()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "이미 지난 회차입니다")
        }
        // 두 번 눌러도 같은 감시 하나다. 새로 만들면 알림이 두 번 간다.
        // **한도 검사보다 먼저 본다** — 이미 내 것인 자리를 다시 눌렀는데
        // "한도 초과" 가 나오면 사용자는 뭘 지워야 할지 알 수 없다
        val existing = watches.findByUserIdAndTimeSlot(userId, slot)
        if (existing != null) return existing.toDto()

        // **목록과 똑같은 쿼리**로 센다. 지난 자리는 목록에서도 빠지고 한도에서도 빠진다 —
        // 둘이 갈라지면 "목록은 비었는데 못 건다" 가 된다
        val used = watches.findActiveByUserId(userId, LocalDate.now(), LocalTime.now()).size
        if (used >= maxPerUser) throw WatchLimitExceeded(maxPerUser)

        return watches.save(Watch(userId, slot, Instant.now())).toDto()
    }

    @Transactional
    fun cancel(userId: String, watchId: Long) {
        val watch = watches.findById(watchId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "그런 감시가 없습니다")
        }
        // 남의 감시를 지울 수 없다. "없다" 로 답한다 — 있다는 사실 자체를 알려 줄 이유가 없다
        if (watch.userId != userId) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "그런 감시가 없습니다")
        }
        watches.delete(watch)
    }

    private fun Watch.toDto() = WatchDto(
        id = requireNotNull(id),
        branch = timeSlot.theme.store.branchName,
        theme = timeSlot.theme.name,
        date = timeSlot.date,
        t = timeSlot.time.hour * 60 + timeSlot.time.minute,
        available = timeSlot.available,
        createdAt = createdAt,
    )
}
