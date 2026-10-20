package com.my_dream.server.club

import com.my_dream.server.domain.ClubEvent
import com.my_dream.server.domain.ClubMember
import com.my_dream.server.domain.ClubMemberRepository
import com.my_dream.server.domain.MemberRole
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

/** 만든 사람이거나 운영진(D28 의 ADMIN·APPROVED). 일정 관리 · 정산(D37) · 응답의 `canManage` 가 전부 이 판정이다 */
fun ClubEvent.manageableBy(viewer: ClubMember): Boolean =
    createdBy == viewer.userId || (viewer.role == MemberRole.ADMIN && viewer.status == MemberStatus.APPROVED)

/**
 * 느슨하게 받은 값(`Any?`)이 **정수인가**. 아니면 null.
 * Int · Long · BigInteger 만 정수다 — 잭슨은 숫자 칸에 온 `1.5` 를 1 로, `"12"` 를 12 로 조용히 바꿔 주므로
 * 칸 타입을 `Int` 로 두면 그런 값이 틀렸다고 말할 수 없다 (D35 방 수 · D37 정산 금액)
 */
fun wholeNumber(raw: Any?): Long? = when (raw) {
    is Int -> raw.toLong()
    is Long -> raw
    is java.math.BigInteger -> if (raw.bitLength() < 63) raw.toLong() else null
    else -> null
}
