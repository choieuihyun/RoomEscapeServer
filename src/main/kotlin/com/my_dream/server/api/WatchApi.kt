package com.my_dream.server.api

import com.my_dream.server.domain.TimeSlotRepository
import com.my_dream.server.domain.Watch
import com.my_dream.server.domain.WatchRepository
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.time.LocalDate

/** 감시를 걸 자리. [slotId] 는 `GET /api/schedule` 이 회차마다 내려준 `id` 다. */
data class WatchRequest(val slotId: Long)

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
    fun list(@AuthenticationPrincipal jwt: Jwt): List<WatchDto> = service.list(jwt.uid())

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

@Service
class WatchService(
    private val watches: WatchRepository,
    private val slots: TimeSlotRepository,
) {

    @Transactional(readOnly = true)
    fun list(userId: String): List<WatchDto> =
        watches.findActiveByUserId(userId, LocalDate.now()).map { it.toDto() }

    @Transactional
    fun register(userId: String, slotId: Long): WatchDto {
        val slot = slots.findById(slotId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "그런 회차가 없습니다")
        }
        // 지난 자리를 지켜보겠다는 건 성립하지 않는다. 조용히 받아 두면 영영 안 울리는 감시가 쌓인다
        if (slot.date < LocalDate.now()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "이미 지난 회차입니다")
        }
        // 두 번 눌러도 같은 감시 하나다. 새로 만들면 알림이 두 번 간다
        val existing = watches.findByUserIdAndTimeSlot(userId, slot)
        if (existing != null) return existing.toDto()

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
