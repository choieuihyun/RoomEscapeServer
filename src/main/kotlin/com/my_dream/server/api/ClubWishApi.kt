package com.my_dream.server.api

import com.my_dream.server.club.ClubError
import com.my_dream.server.club.DeletedDto
import com.my_dream.server.club.PayInfoDto
import com.my_dream.server.club.PayInfoInput
import com.my_dream.server.club.PayInfoService
import com.my_dream.server.club.WishDto
import com.my_dream.server.club.WishGroupDto
import com.my_dream.server.club.WishInput
import com.my_dream.server.club.WishService
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

/**
 * 하고 싶은 테마(D36) · 내 송금 정보(D37). 계약은 Floduler `작업명세서.md` §4.12 · §4.13.
 *
 * 경로는 `/api/me/…` 와 `/api/club/…` 에 걸치지만 **컨트롤러를 따로 둔다** — 깨진 본문의 오류 코드가 컨트롤러마다
 * 다르다(`MeController` 는 `BAD_PROFILE`, `ClubProfileController` 는 `BAD_PLAYED`). 거기에 얹으면 위시의 깨진 본문이 엉뚱한 코드로 나간다.
 */
@RestController
@RequestMapping("/api")
class ClubWishController(
    private val wishes: WishService,
    private val payInfo: PayInfoService,
) {

    @GetMapping("/me/wishes")
    fun mine(@AuthenticationPrincipal jwt: Jwt): List<WishDto> = wishes.mine(jwt.uid())

    @PostMapping("/me/wishes")
    fun add(@AuthenticationPrincipal jwt: Jwt, @RequestBody input: WishInput): WishDto = wishes.add(jwt.uid(), input)

    @DeleteMapping("/me/wishes/{id}")
    fun delete(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: Long): DeletedDto = wishes.delete(jwt.uid(), id)

    @GetMapping("/club/wishes")
    fun club(@AuthenticationPrincipal jwt: Jwt): List<WishGroupDto> = wishes.club(jwt.uid())

    @GetMapping("/me/payinfo")
    fun payInfo(@AuthenticationPrincipal jwt: Jwt): PayInfoDto = payInfo.mine(jwt.uid())

    @PutMapping("/me/payinfo")
    fun savePayInfo(@AuthenticationPrincipal jwt: Jwt, @RequestBody input: PayInfoInput): PayInfoDto = payInfo.save(jwt.uid(), input)
}

/** 이 컨트롤러의 **틀린 입력**도 `{ error, message }` 로. 이 컨트롤러에만 건다 (`ClubEventInputAdvice` 와 같은 이유) */
@RestControllerAdvice(assignableTypes = [ClubWishController::class])
class ClubWishInputAdvice(private val errors: ClubErrorAdvice) {

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun onUnreadable(e: HttpMessageNotReadableException, req: HttpServletRequest): ResponseEntity<Map<String, Any?>> =
        errors.onClubError(if (req.requestURI.endsWith("/payinfo")) ClubError.badPayInfo() else ClubError.badWish())

    /** `/me/wishes/abc` — 그런 위시는 없다 */
    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun onBadId(e: MethodArgumentTypeMismatchException): ResponseEntity<Map<String, Any?>> = errors.onClubError(ClubError.noSuchWish())
}
