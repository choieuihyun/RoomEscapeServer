package com.my_dream.server.api

import org.springframework.http.HttpStatus
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.server.ResponseStatusException

/**
 * Firebase uid. 서명 검증을 통과한 토큰에는 항상 `sub` 가 있지만,
 * 타입상 null 이 가능하니 여기서 한 번만 확인하고 아래로는 `String` 으로 넘긴다.
 *
 * 컨트롤러마다 같은 사본을 두던 것을 하나로 모았다 (2026-10-19 — 셋째 사본이 생기려던 때).
 * 이 판단이 갈라지면 어떤 경로는 `sub` 없는 토큰을 받아들이게 된다.
 */
fun Jwt.uid(): String =
    subject ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "토큰에 sub 가 없습니다")
