package com.my_dream.server.api

import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDate

/**
 * Floduler 가 쓰는 공개 조회 API. 인증 없는 읽기 전용이다.
 * 디버그 엔드포인트와 달리 **배포 대상**이므로 응답 모양을 함부로 바꾸지 않는다 (Floduler 가 깨진다).
 */
@RestController
@RequestMapping("/api")
class ScheduleApiController(private val query: ScheduleQueryService) {

    @GetMapping("/branches")
    fun branches(): List<BranchDto> = query.branches()

    @GetMapping("/schedule")
    fun schedule(
        @RequestParam branch: String,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate,
    ): ScheduleDto = query.schedule(branch, date)
        ?: throw ResponseStatusException(
            HttpStatus.NOT_FOUND,
            "아직 수집되지 않은 지점·날짜다: branch=$branch date=$date",
        )
}
