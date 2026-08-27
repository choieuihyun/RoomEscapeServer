package com.my_dream.server.debug

import com.my_dream.server.crawler.DaySchedule
import com.my_dream.server.crawler.play33.Play33Branch
import com.my_dream.server.crawler.play33.Play33Crawler
import com.my_dream.server.domain.StoreRepository
import com.my_dream.server.domain.ThemeRepository
import com.my_dream.server.domain.TimeSlotRepository
import com.my_dream.server.sync.Play33Collector
import com.my_dream.server.sync.StoreCollector
import com.my_dream.server.sync.SweepSummary
import com.my_dream.server.sync.SyncResult
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

/**
 * 손으로 확인하는 엔드포인트. **인증이 없다.**
 *
 * 기본은 꺼져 있고, 켜려면 `DEBUG_ENDPOINTS=true` 로 띄운다.
 * 배포에서는 이 값이 false 이고 Caddy 도 `/api` 밖은 라우팅하지 않는다 — 두 겹으로 막는다.
 */
@ConditionalOnProperty(prefix = "debug", name = ["endpoints-enabled"], havingValue = "true")
@RestController
class Play33DebugController(
    private val crawler: Play33Crawler,
    private val collector: Play33Collector,
    private val sweeper: StoreCollector,
    private val stores: StoreRepository,
    private val themes: ThemeRepository,
    private val slots: TimeSlotRepository,
) {

    /** 사이트에서 받아온 날것. DB 를 거치지 않는다. */
    @GetMapping("/debug/play33")
    fun fetch(
        @RequestParam(defaultValue = "KONKUK") branch: Play33Branch,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate,
    ): DaySchedule = crawler.fetch(branch, date)

    /** 한 지점·하루를 수집해 저장한다. 두 번 부르면 두 번째부터 전이가 잡힌다. */
    @GetMapping("/debug/collect")
    fun collect(
        @RequestParam(defaultValue = "KONKUK") branch: Play33Branch,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate,
    ): SyncResult = collector.collectOne(branch, date)

    /**
     * 전 지점 한 바퀴. **스케줄러를 켜지 않고** 수집 한 번을 손으로 돌려본다.
     *
     * 매장 사이가 병렬로 도는지, 한 바퀴가 주기 안에 끝나는지를 확인하는 자리다 (D13).
     * 실제 요청이 `지점수 × 7`번 나가므로 아무 때나 누르지 않는다.
     */
    @GetMapping("/debug/sweep")
    fun sweep(): SweepSummary = sweeper.collectAll()

    /** DB 에 실제로 뭐가 들어갔는지. */
    @GetMapping("/debug/stored")
    @Transactional(readOnly = true)
    fun stored(
        @RequestParam(defaultValue = "play33-konkuk") branch: String,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate,
    ): Map<String, Any?> {
        val store = stores.findByStoreKey(branch) ?: return mapOf("error" to "저장된 매장이 없다: $branch")
        return mapOf(
            "store" to "${store.brand} ${store.branchName}",
            "date" to date,
            "themes" to themes.findByStore(store).map { theme ->
                val rows = slots.findByThemeAndDate(theme, date).sortedBy { it.time }
                mapOf(
                    "name" to theme.name,
                    "runningMinutes" to theme.runningMinutes,
                    "available" to rows.count { it.available },
                    "soldOut" to rows.count { !it.available },
                    "slots" to rows.map { "${it.time}${if (it.available) "" else " (매진)"}" },
                )
            },
        )
    }
}
