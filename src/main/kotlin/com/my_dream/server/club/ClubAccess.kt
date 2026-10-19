package com.my_dream.server.club

import com.my_dream.server.domain.ClubMember
import com.my_dream.server.domain.ClubMemberRepository
import com.my_dream.server.domain.MemberStatus
import java.time.LocalDate
import java.time.ZoneId

/**
 * 승인된 멤버인가 — 동호회 API 전부의 첫 줄. 서비스마다 따로 쓰면 하나를 빠뜨렸을 때 그 경로만 조용히 열린다
 */
fun ClubMemberRepository.requireApproved(uid: String): ClubMember =
    findByUserId(uid)?.takeIf { it.status == MemberStatus.APPROVED } ?: throw ClubError.notClubMember()

/**
 * 동호회의 "오늘" 은 한국 날짜다. **명시한다** — 운영 컨테이너는 `TZ=Asia/Seoul` 이지만 VM 은 UTC 고
 * (백업 크론이 13시에 돌았던 사고 — 배포.md), 테스트는 개발 머신 시간대로 돈다
 */
val KST: ZoneId = ZoneId.of("Asia/Seoul")

fun todayKst(): LocalDate = LocalDate.now(KST)
