package com.my_dream.server.api

import com.my_dream.server.notify.DeviceTokens
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * [token] 은 브라우저가 `getToken({ vapidKey })` 로 받아 온 FCM 등록 토큰.
 * [platform] 은 나중에 실패 원인을 나눠 보기 위한 꼬리표다 — `web` / `android` / `ios`.
 */
data class DeviceRequest(val token: String, val platform: String? = null)

/**
 * 알림 받을 기기 등록 — "이 브라우저로 보내줘".
 *
 * **감시(`/api/watches`)와 나눠 둔 이유:** 감시는 "무엇을 지켜볼지" 고 이건 "어디로 보낼지" 다.
 * 기기 하나로 감시를 열 개 걸 수 있고, 감시를 하나도 안 걸어 둔 채 기기만 등록해 둘 수도 있다.
 * 한 요청에 묶으면 토큰이 만료됐을 때 감시 열 건을 전부 고쳐야 한다.
 *
 * 브라우저는 **앱을 열 때마다** 이걸 부른다. 토큰은 조용히 갱신되기 때문에
 * "한 번 등록하고 끝" 으로 두면 어느 날부터 알림이 안 온다. 그래서 등록은 멱등한 upsert 다.
 */
@RestController
@RequestMapping("/api/devices")
class DeviceController(private val devices: DeviceTokens) {

    @PostMapping
    fun register(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestBody request: DeviceRequest,
    ): ResponseEntity<Void> {
        val token = request.token.trim()
        // 빈 토큰을 받아 두면 발송 때마다 확실히 실패하는 주소가 하나 늘어난다
        if (token.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "토큰이 비어 있습니다")
        }
        devices.register(jwt.uid(), token, request.platform)
        return ResponseEntity.noContent().build()
    }

    /**
     * 로그아웃하거나 알림을 끌 때. **경로가 아니라 본문으로 받는다** —
     * FCM 토큰에는 `:` 와 `/` 가 섞여 있어서 URL 경로에 그대로 넣으면 프록시마다 다르게 해석한다.
     */
    @DeleteMapping
    fun unregister(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestBody request: DeviceRequest,
    ): ResponseEntity<Void> {
        // 남의 토큰을 지울 수 없다. 내 것이 아니면 조용히 무시한다 —
        // "그런 토큰 없다" 와 "남의 것이다" 를 구분해 주면 남의 토큰 존재를 확인하는 수단이 된다
        if (request.token in devices.of(jwt.uid())) devices.forget(request.token)
        return ResponseEntity.noContent().build()
    }

    private fun Jwt.uid(): String =
        subject ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "토큰에 sub 가 없습니다")
}
