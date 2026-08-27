package com.my_dream.server.crawler.keyescape

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/**
 * 지점의 테마 목록과 메타데이터. **하루에 한 번만 받아 온다.**
 *
 * 테마는 자주 바뀌지 않는데 받아 오는 값은 비싸다 —
 * 지점 11개 + 테마 32개 = **43요청**이다. 매 바퀴 받으면 그것만으로 1분이 넘는다.
 *
 * 갱신 요청도 [KeyescapeClient] 를 거치므로 다른 요청과 똑같이 줄을 선다 (아키텍처 D15).
 * 대신 **갱신이 걸린 바퀴는 그만큼 길어진다** — 하루 한 번이라 감수한다.
 */
@Component
class KeyescapeThemeCatalog(private val client: KeyescapeClient) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val cache = ConcurrentHashMap<KeyescapeBranch, Cached>()

    private data class Cached(val on: LocalDate, val themes: List<KeyescapeTheme>)

    fun themes(branch: KeyescapeBranch, today: LocalDate = LocalDate.now()): List<KeyescapeTheme> {
        val hit = cache[branch]
        if (hit != null && hit.on == today) return hit.themes

        return try {
            load(branch).also { cache[branch] = Cached(today, it) }
        } catch (e: Exception) {
            // **실패했다고 목록을 비우지 않는다.** 비우면 그 지점이 통째로 수집에서 빠지고,
            // 조회 API 는 "테마가 없는 지점" 으로 보인다 — 조회 실패와 결과 없음은 다른 말이다
            log.warn("테마 목록 갱신 실패 — {} : {}. 이전 목록을 계속 쓴다", branch.branchName, e.message)
            hit?.themes ?: emptyList()
        }
    }

    private fun load(branch: KeyescapeBranch): List<KeyescapeTheme> {
        val rows = client.themeList(branch)
        if (rows.isEmpty()) {
            log.warn("테마가 하나도 없다 — {}. 지점이 비었는지 파서가 깨졌는지 확인이 필요하다", branch.branchName)
        }
        return rows.map { row ->
            // 상세는 없어도 회차 수집은 된다. 메타데이터 하나 때문에 지점을 통째로 날리지 않는다
            val detail = runCatching { client.themeDetail(row.infoNum) }.getOrNull()
            KeyescapeTheme(
                infoNum = row.infoNum,
                themeNum = row.themeNum,
                name = row.infoName,
                genre = detail?.genre,
                difficulty = detail?.level?.decimalOrNull(),
                runningMinutes = detail?.playTime?.digitsOrNull(),
                posterUrl = detail?.imageUrl,
            )
        }.also { log.info("테마 목록 갱신 — {} {}개", branch.branchName, it.size) }
    }

    /** `"75분"` → `75` */
    private fun String.digitsOrNull(): Int? = filter { it.isDigit() }.toIntOrNull()

    /**
     * `"4"` → `4.0`, `"4.5"` → `4.5`.
     *
     * 소수가 실제로 오는지는 아직 못 봤지만, 플레이33이 같은 자리에서 0.5 단위였다
     * (아키텍처 D10). 정수로 받아 두면 `"4.5"` 가 `45` 가 되는데 그건 조용히 틀린다.
     */
    private fun String.decimalOrNull(): Double? =
        Regex("""\d+(?:\.\d+)?""").find(this)?.value?.toDoubleOrNull()
}

/** 카탈로그 한 줄. [infoNum] 과 [themeNum] 을 **항상 짝으로** 들고 다닌다 */
data class KeyescapeTheme(
    val infoNum: Int,
    val themeNum: Int,
    val name: String,
    val genre: String?,
    val difficulty: Double?,
    val runningMinutes: Int?,
    val posterUrl: String?,
)
