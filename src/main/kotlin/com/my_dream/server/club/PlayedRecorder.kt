package com.my_dream.server.club

import com.my_dream.server.domain.ClubEventParticipantRepository
import com.my_dream.server.domain.ClubEventRepository
import com.my_dream.server.domain.ClubEventSegmentRepository
import com.my_dream.server.domain.PlayedSource
import com.my_dream.server.domain.PlayedTheme
import com.my_dream.server.domain.PlayedThemeRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate

/**
 * 예약 완료 일정이 지나면 참여자 × 구간을 해 본 테마로 넣는다 (아키텍처 D33 · 계약 §4.9).
 *
 * **일정마다 한 번만** 한다(`played_recorded_at`). 볼 때마다 계산하지 않는 이유: 사람이 지운 기록이 다음 화면에서 되살아난다.
 * 서버가 하루 쉬어도 표시 안 된 일정은 다음 날 같이 처리된다. 기록한 뒤 일정을 고쳐도 다시 하지 않는다.
 */
@Component
class PlayedRecorder(
    private val events: ClubEventRepository,
    private val segments: ClubEventSegmentRepository,
    private val participants: ClubEventParticipantRepository,
    private val played: PlayedThemeRepository,
    private val wishes: WishService,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** 04:30 KST — 백업(04:15)이 끝난 뒤. **zone 을 못 박는다** — VM 은 UTC 다 */
    @Scheduled(cron = "0 30 4 * * *", zone = "Asia/Seoul")
    fun nightly() {
        val (eventCount, added) = record(todayKst())
        if (eventCount > 0) log.info("해 본 테마 자동 기록 — 일정 {}개, 새 기록 {}개", eventCount, added)
    }

    /** @return (표시한 일정 수, 새로 넣은 기록 수) */
    @Transactional
    fun record(today: LocalDate): Pair<Int, Int> {
        val targets = events.findUnrecordedPast(today)
        if (targets.isEmpty()) return 0 to 0
        val ids = targets.map { requireNotNull(it.id) }
        val segsByEvent = segments.findByEventIds(ids).groupBy { requireNotNull(it.event.id) }
        val peopleByEvent = participants.findByEventIds(ids).groupBy { requireNotNull(it.event.id) }
        val uids = peopleByEvent.values.flatten().map { it.userId }.toSet()
        val have = played.findByUserIdIn(uids).groupByTo(mutableMapOf()) { it.userId }

        val now = Instant.now()
        val created = mutableListOf<PlayedTheme>()
        for (event in targets) {
            val id = requireNotNull(event.id)
            for (person in peopleByEvent[id].orEmpty()) {
                val mine = have.getOrPut(person.userId) { mutableListOf() }
                for (seg in segsByEvent[id].orEmpty()) {
                    val nameKey = PlayedMatch.nameKey(seg.themeName)
                    if (mine.any { PlayedMatch.same(it.themeKey, it.nameKey, seg.themeKey, nameKey) }) continue
                    created += played.save(
                        PlayedTheme(person.userId, seg.themeKey, seg.themeName, seg.branchName, nameKey, event.date, PlayedSource.EVENT, id, now),
                    ).also { mine += it }
                }
            }
        }
        // 벌크로 단다 — 엔티티로 고치면 version 이 올라 그 일정을 열어 둔 사람이 충돌을 받는다 (D33)
        events.markPlayedRecorded(ids, now)
        // 해 본 테마가 된 하고 싶은 테마를 지운다 (D36) — 사람마다가 아니라 한 번에 읽는다
        wishes.forgetPlayed(created)
        return targets.size to created.size
    }
}
