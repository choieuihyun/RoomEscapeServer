package com.my_dream.server.sync

import com.my_dream.server.crawler.DaySchedule
import com.my_dream.server.crawler.StoreRef
import com.my_dream.server.crawler.ThemeSchedule
import com.my_dream.server.domain.Store
import com.my_dream.server.domain.StoreRepository
import com.my_dream.server.domain.Theme
import com.my_dream.server.domain.ThemeRepository
import com.my_dream.server.domain.TimeSlot
import com.my_dream.server.domain.TimeSlotRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * 수집 결과를 저장하고, 저장 **전에** 이전 상태와 비교해 전이를 뽑아낸다.
 *
 * 지금은 플레이33 전용 타입([DaySchedule])을 직접 받는다. 두 번째 매장이 생기면
 * 그때 공통 DTO 를 만든다 — 매장이 하나뿐인 상태에서 미리 추상화하면 틀린 모양이 나온다.
 */
@Service
class ScheduleSyncService(
    private val stores: StoreRepository,
    private val themes: ThemeRepository,
    private val slots: TimeSlotRepository,
) {

    @Transactional
    fun sync(day: DaySchedule): SyncResult {
        val store = findOrCreateStore(day.store)
        val now = Instant.now()
        val transitions = mutableListOf<SlotTransition>()
        val quarantined = mutableListOf<QuarantineReport>()

        for (incoming in day.themes) {
            val theme = findOrUpdateTheme(store, incoming)
            val existing = slots.findByThemeAndDate(theme, day.date).associateBy { it.time }

            // 이전에 매진이던 회차가 이번에 풀렸는가 — 저장하기 전에 비교해야 알 수 있다
            val previouslyUnavailable = existing.values.count { !it.available }
            val flipped = incoming.slots.filter { it.available && existing[it.time]?.available == false }

            val suspicious = isSuspicious(previouslyUnavailable, flipped.size)
            var emitTransitions = true

            if (suspicious) {
                val streak = quarantineStreaks.merge(streakKey(theme, day), 1, Int::plus) ?: 1
                // 계속 건너뛰기만 하면 기준선이 낡은 채로 남아 매번 같은 판정이 나온다 — 스스로 못 빠져나온다.
                // 같은 판정이 반복되면 저장은 해서 기준선을 되살리되, 알림은 끝까지 막는다
                val recovered = streak >= MAX_CONSECUTIVE_QUARANTINES
                quarantined += QuarantineReport(
                    theme.name, day.date, previouslyUnavailable, flipped.size, streak, recovered,
                )
                if (!recovered) continue
                quarantineStreaks.remove(streakKey(theme, day))
                emitTransitions = false
            } else {
                quarantineStreaks.remove(streakKey(theme, day))
            }

            for (slot in incoming.slots) {
                val row = existing[slot.time]
                if (row == null) {
                    slots.save(TimeSlot(theme, day.date, slot.time, slot.available, now))
                } else {
                    // 트랜잭션 안이라 변경 감지로 UPDATE 된다. 지웠다 넣지 않는다
                    row.available = slot.available
                    row.lastCheckedAt = now
                }
            }

            if (emitTransitions) {
                transitions += flipped.map {
                    // flipped 는 "이전에 매진이던" 회차라 existing 에 반드시 있다
                    val row = requireNotNull(existing[it.time]) { "flipped 인데 이전 행이 없다: ${it.time}" }
                    SlotTransition(
                        timeSlotId = requireNotNull(row.id),
                        storeKey = store.storeKey,
                        branchName = store.branchName,
                        themeName = theme.name,
                        date = day.date,
                        time = it.time,
                    )
                }
            }
        }

        return SyncResult(transitions, quarantined)
    }

    /**
     * 위생 검사 (아키텍처 D6). 파서가 깨져 모든 회차를 `예약 가능` 으로 읽으면
     * 전 사용자에게 헛알림이 나가고 그걸로 서비스 신뢰가 끝난다.
     *
     * 표본이 너무 작으면(매진 2개 중 1개 해제 등) 정상 상황도 걸리므로 하한을 둔다.
     */
    private fun isSuspicious(previouslyUnavailable: Int, flipped: Int): Boolean =
        previouslyUnavailable >= MIN_SAMPLE_FOR_SUSPICION && flipped * 2 > previouslyUnavailable

    private fun streakKey(theme: Theme, day: DaySchedule) = "${theme.id}:${day.date}"

    private fun findOrCreateStore(ref: StoreRef): Store =
        stores.findByStoreKey(ref.key)?.apply {
            brand = ref.brand
            branchName = ref.branchName
        } ?: stores.save(Store(ref.key, ref.brand, ref.branchName))

    /** 테마 메타데이터(장르·인원·소요시간 등)는 볼 때마다 최신으로 덮어쓴다. */
    private fun findOrUpdateTheme(store: Store, incoming: ThemeSchedule): Theme {
        val externalId = incoming.externalId ?: incoming.themeName
        val theme = themes.findByStoreAndExternalId(store, externalId)
            ?: return themes.save(
                Theme(
                    store = store,
                    externalId = externalId,
                    name = incoming.themeName,
                    genre = incoming.genre,
                    capacity = incoming.capacity,
                    runningMinutes = incoming.runningMinutes,
                    horrorLevel = incoming.horrorLevel,
                    difficulty = incoming.difficulty,
                    posterUrl = incoming.posterUrl,
                ),
            )

        theme.name = incoming.themeName
        theme.genre = incoming.genre
        theme.capacity = incoming.capacity
        theme.runningMinutes = incoming.runningMinutes
        theme.horrorLevel = incoming.horrorLevel
        theme.difficulty = incoming.difficulty
        theme.posterUrl = incoming.posterUrl
        return theme
    }

    /**
     * `(테마, 날짜)` 별 연속 격리 횟수. 재시작하면 사라지는데, 그래도 된다 —
     * 최악이라도 수집 한 바퀴를 더 건너뛸 뿐이다.
     */
    private val quarantineStreaks = ConcurrentHashMap<String, Int>()

    companion object {
        /** 이전 매진 회차가 이 수보다 적으면 위생 검사를 적용하지 않는다 */
        private const val MIN_SAMPLE_FOR_SUSPICION = 4

        /** 같은 판정이 이만큼 연속되면 저장은 해서 기준선을 되살린다 (알림은 여전히 안 나간다) */
        private const val MAX_CONSECUTIVE_QUARANTINES = 3
    }
}
