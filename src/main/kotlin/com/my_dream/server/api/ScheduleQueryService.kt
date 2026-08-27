package com.my_dream.server.api

import com.my_dream.server.crawler.play33.Play33Branch
import com.my_dream.server.domain.Store
import com.my_dream.server.domain.StoreRepository
import com.my_dream.server.domain.Theme
import com.my_dream.server.domain.TimeSlot
import com.my_dream.server.domain.TimeSlotRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/**
 * 저장해 둔 시간표를 읽어 외부에 줄 모양으로 바꾼다.
 *
 * **사이트를 직접 긁지 않는다.** 조회 요청이 곧 크롤이 되면 외부에서 우리 크롤 빈도를 좌우하게 된다.
 * 신선도는 수집 스케줄러가 책임지고, 이 API 는 `checkedAt` 으로 언제 기준인지만 밝힌다.
 */
@Service
@Transactional(readOnly = true)
class ScheduleQueryService(
    private val stores: StoreRepository,
    private val slots: TimeSlotRepository,
) {

    /** 지원하는 지점 전부. 아직 수집 안 된 지점은 [BranchDto.dates] 가 비어 있다. */
    fun branches(today: LocalDate = LocalDate.now()): List<BranchDto> = Play33Branch.entries.map { branch ->
        val store = stores.findByStoreKey(branch.key)
        val dates = store?.let { slots.findDatesByStore(it, today) }.orEmpty()
        BranchDto(
            id = branch.key,
            store = Play33Branch.BRAND,
            branch = branch.branchName,
            dates = dates,
            checkedAt = store?.let { s ->
                dates.firstOrNull()?.let { d -> slots.findByStoreAndDate(s, d).maxOfOrNull { it.lastCheckedAt } }
            },
        )
    }

    /** 없는 지점이거나 아직 수집되지 않았으면 null. 호출자가 그 사실을 구분해 알릴 수 있게 한다. */
    fun schedule(branchKey: String, date: LocalDate): ScheduleDto? {
        // 모르는 키로 DB 를 뒤지지 않는다. 지원 목록에 있는 지점만 응답한다
        Play33Branch.entries.firstOrNull { it.key == branchKey } ?: return null
        val store = stores.findByStoreKey(branchKey) ?: return null
        val rows = slots.findByStoreAndDate(store, date)
        if (rows.isEmpty()) return null

        return ScheduleDto(
            store = store.brand,
            branch = store.branchName,
            date = date,
            checkedAt = rows.maxOf { it.lastCheckedAt },
            themes = rows.groupBy { it.theme }.map { (theme, themeSlots) ->
                theme.toDto(store, themeSlots)
            },
        )
    }

    private fun Theme.toDto(store: Store, themeSlots: List<TimeSlot>): ThemeDto {
        val (min, max) = capacity.toPeopleRange()
        return ThemeDto(
            id = "${store.storeKey}:$externalId",
            name = name,
            place = "${store.brand} ${store.branchName}",
            dur = runningMinutes,
            genre = genre,
            capacity = capacity,
            minPeople = min,
            maxPeople = max,
            horrorLevel = horrorLevel,
            difficulty = difficulty,
            posterUrl = posterUrl,
            // 매진 회차도 그대로 넘긴다. 걸러내는 건 화면이 정할 일이다
            sessions = themeSlots.sortedBy { it.time }.map { it.toSession() },
        )
    }

    /**
     * `2~3인` -> `2..3`, `4인` -> `4..4`. 못 읽으면 둘 다 null.
     *
     * 저장은 사이트 표기 그대로 하고 여기서 파생시킨다 — 사이트가 표기를 바꿔도 저장된 값은 사실 그대로 남는다.
     */
    private fun String?.toPeopleRange(): Pair<Int?, Int?> {
        val numbers = this?.let { Regex("\\d+").findAll(it).map { m -> m.value.toInt() }.toList() }.orEmpty()
        return when (numbers.size) {
            0 -> null to null
            1 -> numbers[0] to numbers[0]
            else -> numbers.min() to numbers.max()
        }
    }

    /** `10:35` → `635`. 자정부터의 분. */
    private fun TimeSlot.toSession() = SessionDto(
        id = requireNotNull(id),
        t = time.hour * 60 + time.minute,
        soldout = !available,
    )
}
