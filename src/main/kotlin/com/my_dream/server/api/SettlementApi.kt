package com.my_dream.server.api

import com.my_dream.server.club.ClubError
import com.my_dream.server.club.SettlementDto
import com.my_dream.server.club.SettlementInput
import com.my_dream.server.club.SettlementService
import com.my_dream.server.club.TransferInput
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
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

/**
 * 정산 (아키텍처 D37). 계약은 Floduler `작업명세서.md` §4.13. 누가 볼 수 있는지·송금 계산은 전부 [SettlementService] 가 한다.
 * 일정 컨트롤러와 나눈 이유는 오류 코드다 — 깨진 본문이 여기서는 `BAD_SETTLEMENT` 여야 한다
 */
@RestController
@RequestMapping("/api/club/events/{id}/settlement")
class SettlementController(private val service: SettlementService) {

    @GetMapping
    fun get(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: Long): SettlementDto = service.get(jwt.uid(), id)

    @PutMapping
    fun save(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: Long, @RequestBody input: SettlementInput): SettlementDto =
        service.save(jwt.uid(), id, input)

    @PostMapping("/transfers")
    fun mark(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: Long, @RequestBody input: TransferInput): SettlementDto =
        service.mark(jwt.uid(), id, input)
}

@RestControllerAdvice(assignableTypes = [SettlementController::class])
class SettlementInputAdvice(private val errors: ClubErrorAdvice) {

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun onUnreadable(e: HttpMessageNotReadableException): ResponseEntity<Map<String, Any?>> = errors.onClubError(ClubError.badSettlement())

    /** `/events/abc/settlement` — 그런 일정은 없다 */
    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun onBadId(e: MethodArgumentTypeMismatchException): ResponseEntity<Map<String, Any?>> = errors.onClubError(ClubError.noSuchEvent())
}
