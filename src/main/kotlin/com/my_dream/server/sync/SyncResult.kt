package com.my_dream.server.sync

import java.time.LocalDate
import java.time.LocalTime

/** `예약 불가 → 예약 가능` 으로 바뀐 회차 하나. 이 서비스가 존재하는 이유다. */
data class SlotTransition(
    /** 감시 신청과 맞춰 보려면 어느 행이 풀렸는지가 필요하다. 이름·시각만으로는 못 찾는다 */
    val timeSlotId: Long,
    val storeKey: String,
    val branchName: String,
    val themeName: String,
    val date: LocalDate,
    val time: LocalTime,
)

/**
 * 위생 검사에 걸린 수집분 (아키텍처 D6).
 * 파서가 깨졌을 가능성이 높으므로 사용자가 아니라 운영자가 봐야 한다.
 *
 * [recovered] 가 true 면 같은 판정이 [consecutive] 번 반복돼 **이번에는 저장했다**는 뜻이다.
 * 저장을 계속 건너뛰면 기준선이 영영 낡은 채로 남아 매번 같은 판정이 나온다 — 스스로 못 빠져나온다.
 * 다만 저장은 하되 **전이는 내보내지 않는다.** 한 번에 수십 건이 알림으로 나가는 게 D6 이 막으려던 바로 그 사고다.
 */
data class QuarantineReport(
    val themeName: String,
    val date: LocalDate,
    val previouslyUnavailable: Int,
    val flipped: Int,
    val consecutive: Int,
    val recovered: Boolean,
)

data class SyncResult(
    val transitions: List<SlotTransition>,
    val quarantined: List<QuarantineReport>,
)
