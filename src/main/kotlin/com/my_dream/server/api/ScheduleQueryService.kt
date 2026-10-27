package com.my_dream.server.api

import com.my_dream.server.crawler.StoreAdapter
import com.my_dream.server.domain.Store
import com.my_dream.server.domain.StoreRepository
import com.my_dream.server.domain.Theme
import com.my_dream.server.domain.ThemeRepository
import com.my_dream.server.domain.TimeSlot
import com.my_dream.server.domain.TimeSlotRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.text.Collator
import java.time.LocalDate
import java.util.Locale

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
    private val themes: ThemeRepository,
    private val adapters: List<StoreAdapter>,
) {

    /**
     * 지원 지점 목록은 **어댑터가 알고 있다.** 조회 API 가 매장 종류를 알 필요가 없다.
     * 매장을 추가하면 어댑터 하나만 늘리면 여기까지 따라온다.
     */
    fun supportedBranches() = adapters.flatMap { it.branches }

    /**
     * 오늘이 지났는데 아직 지점 예약 창 앞인 날짜인가 (D42). 그 자리 행은 수집이 더는 고치지 않는 옛 값이다.
     * **지난 날짜는 건드리지 않는다** — 그건 원래부터 그대로 주던 값이다
     */
    fun isBeforeWindow(storeKey: String, date: LocalDate, today: LocalDate = LocalDate.now()): Boolean {
        val adapter = adapters.firstOrNull { a -> a.branches.any { it.key == storeKey } } ?: return false
        return !date.isBefore(today) && date.isBefore(adapter.firstOpenDay(storeKey, today))
    }

    /** 지원하는 지점 전부. 아직 수집 안 된 지점은 [BranchDto.dates] 가 비어 있다. */
    fun branches(today: LocalDate = LocalDate.now()): List<BranchDto> = supportedBranches().map { branch ->
        val store = stores.findByStoreKey(branch.key)
        val dates = store?.let { slots.findDatesByStore(it, today) }.orEmpty()
            .filterNot { isBeforeWindow(branch.key, it, today) }
        BranchDto(
            id = branch.key,
            store = branch.brand,
            branch = branch.branchName,
            dates = dates,
            checkedAt = store?.let { s ->
                dates.firstOrNull()?.let { d -> slots.findByStoreAndDate(s, d).maxOfOrNull { it.lastCheckedAt } }
            },
            region = BranchRegions.of(branch.key),
        )
    }

    /**
     * 해 본 테마 고르기용 (§4.9 `GET /api/themes`). **지원 지점의 테마 전부** — 사이트에서 사라진 테마도 남긴다.
     * 해 본 테마는 대개 지난 테마라, 지금 열린 것만 주면 기록할 수가 없다 (D33)
     */
    fun themes(): List<ThemeRefDto> {
        val korean = Collator.getInstance(Locale.KOREAN)
        return catalog()
            .map { ThemeRefDto("${it.store.storeKey}:${it.externalId}", it.name, it.store.branchName, it.store.brand) }
            .sortedWith(
                compareBy<ThemeRefDto, String>(korean) { it.storeName }
                    .thenBy(korean) { it.branchName }
                    .thenBy(korean) { it.themeName },
            )
    }

    /**
     * 지원 지점의 테마 전부(지점까지 읽어 둔 것). `/api/themes` 와 협의 도우미의 "안 모은 날"(§4.9 추가)이 **같은 목록**을 쓴다 —
     * 두 곳이 따로 거르면 한쪽에만 있는 테마가 생긴다. 사이트에서 사라진 테마도 남는다(위 [themes] 와 같은 이유)
     */
    fun catalog(): List<Theme> {
        val supported = supportedBranches().map { it.key }.toSet()
        return themes.findAllWithStore().filter { it.store.storeKey in supported }
    }

    /** 없는 지점이거나 아직 수집되지 않았으면 null. 호출자가 그 사실을 구분해 알릴 수 있게 한다. */
    fun schedule(branchKey: String, date: LocalDate, today: LocalDate = LocalDate.now()): ScheduleDto? {
        // 모르는 키로 DB 를 뒤지지 않는다. 지원 목록에 있는 지점만 응답한다
        supportedBranches().firstOrNull { it.key == branchKey } ?: return null
        if (isBeforeWindow(branchKey, date, today)) return null
        val store = stores.findByStoreKey(branchKey) ?: return null
        val rows = slots.findByStoreAndDate(store, date)
        if (rows.isEmpty()) return null

        return ScheduleDto(
            store = store.brand,
            branch = store.branchName,
            date = date,
            checkedAt = rows.maxOf { it.lastCheckedAt },
            themes = rows.groupBy { it.theme }.map { (theme, themeSlots) ->
                theme.toThemeDto(store, themeSlots)
            },
        )
    }

}

/** `/api/schedule` 의 테마 모양. 협의 도우미(D33)도 같은 모양을 쓴다 — 회차 id 가 같이 가야 거기서 바로 빈자리 알림을 건다 */
internal fun Theme.toThemeDto(store: Store, themeSlots: List<TimeSlot>): ThemeDto {
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
