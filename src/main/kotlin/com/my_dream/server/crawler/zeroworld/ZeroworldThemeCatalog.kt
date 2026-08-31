package com.my_dream.server.crawler.zeroworld

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/**
 * 지점의 테마 목록. **하루에 한 번만 받아 온다** — 키이스케이프와 같은 이유이자 같은 모양이다.
 *
 * 여기는 더 확실하다: `act=theme_list` 가 `rev_days` 를 받는데도 **응답이 날짜와 무관하다.**
 * 10일 떨어진 두 날짜의 응답이 바이트까지 같았다 (2026-08-31 확인).
 * 날짜마다 받으면 한 바퀴에 4요청씩 그냥 버리는 셈이다.
 */
@Component
class ZeroworldThemeCatalog(private val client: ZeroworldClient, private val parser: ZeroworldParser) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val cache = ConcurrentHashMap<ZeroworldBranch, Cached>()

    private data class Cached(val on: LocalDate, val themes: List<ZeroworldTheme>)

    fun themes(branch: ZeroworldBranch, today: LocalDate = LocalDate.now()): List<ZeroworldTheme> {
        val hit = cache[branch]
        if (hit != null && hit.on == today) return hit.themes

        return try {
            load(branch, today).also { cache[branch] = Cached(today, it) }
        } catch (e: Exception) {
            // **실패했다고 목록을 비우지 않는다.** 비우면 그 지점이 통째로 수집에서 빠지고,
            // 조회 API 는 "테마가 없는 지점" 으로 보인다 — 조회 실패와 결과 없음은 다른 말이다
            log.warn("테마 목록 갱신 실패 — {} : {}. 이전 목록을 계속 쓴다", branch.branchName, e.message)
            hit?.themes ?: emptyList()
        }
    }

    private fun load(branch: ZeroworldBranch, today: LocalDate): List<ZeroworldTheme> {
        val themes = parser.themes(client.themeList(branch, today))
        if (themes.isEmpty()) {
            log.warn("테마가 하나도 없다 — {}. 지점이 비었는지 파서가 깨졌는지 확인이 필요하다", branch.branchName)
        }
        return themes.also { log.info("테마 목록 갱신 — {} {} {}개", branch.brand, branch.branchName, it.size) }
    }
}
