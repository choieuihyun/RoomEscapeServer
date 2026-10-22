package com.my_dream.server.crawler.murderparker

import com.my_dream.server.crawler.CrawlHttp
import com.my_dream.server.crawler.DaySchedule
import com.my_dream.server.crawler.FetchUnit
import com.my_dream.server.crawler.HostRateLimiter
import com.my_dream.server.crawler.Slot
import com.my_dream.server.crawler.StoreAdapter
import com.my_dream.server.crawler.StoreRef
import com.my_dream.server.crawler.ThemeSchedule
import com.my_dream.server.crawler.openWithin
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import java.nio.charset.Charset
import java.time.LocalDate
import java.time.LocalTime

/**
 * **머더파커** 홍대 두 지점 (`murderparker.com` · ASP 솔루션 변형 · cp949 · 2026-10-22 조사).
 *
 * ```
 * POST /sub_02/sub02_1.html   JIJEM=S9&D_ROOM=&H_Date=YYYY-MM-DD   → 지점 하루치 전 테마 (화면이 날짜를 바꿀 때 보내는 폼 그대로)
 *   <OPTION value='sub02_1.html?JIJEM=S9&D_ROOM=A'>구둣방손님_홍대1호점                ← 테마 코드 · 지점 꼬리표
 *   <div class="reservTime"> <h3>구둣방손님_홍대1호점</h3>
 *     <a href="/sub_02/sub02_2.html?JIJEM_CODE=S9&CHOIS_DATE=…&ROOM_CODE=A&ROOM_TIME=15:00…" /><li>…15:00   ← 가능
 *     <a href="#" /><li>…11:15 … 예약완료                                                                   ← 불가
 * ```
 *
 * ### 창을 밝히지 않는다 — 그런데 **진짜로 다 열려 있다**
 * 페이지 어디에도 "최대일수" 가 없고 달력은 2018~2050 이다. +365 도 전 회차 가능 + 예약 링크다. 처음엔 "창 밖도 가능으로 그리는 가짜 빈자리" 로
 * 보고 결정 대기에 두었는데, **사용자가 사이트에서 직접 먼 날짜도 예약이 열려 있는 것을 확인했다**(2026-10-22). 매장이 실제로 다 받는 것이다.
 * 그래서 창을 따로 박지 않고, 우리가 늘 보는 범위(계층 폴링 +14 · D21)만 묻는다. 평일 오전 회차가 몇 주 뒤까지 "예약완료" 인 건 매장이 막아 둔 자리로 보인다.
 *
 * 🕳️ **hidden `JIJEM` 은 보낸 값을 돌려준다**(없는 지점 S99 도 S99 · 테마 0개). 그래서 **테마 목록의 지점 번호와 이름 꼬리표**(`_홍대1호점`)로 대조한다 —
 * `JIJEM` 이 무시되고 기본 지점(전주 1호점)이 답하면 꼬리표가 달라 끊긴다. 마크업이 `<a … />` 로 자기 닫는 비표준이라 jsoup 대신 원문 패턴으로 읽는다.
 */
data class MurderParkerBranch(
    val jijem: String,
    val branchName: String,
    val key: String,
    /** 테마 이름 끝에 붙는 지점 꼬리표 — 지점 대조에 쓰고, 이름에서는 뗀다 */
    val suffix: String,
) {
    val storeRef: StoreRef get() = StoreRef(key = key, brand = MurderParker.BRAND, branchName = branchName)
}

object MurderParker {
    const val BRAND = "머더파커"
    const val HOST = "murderparker.com"

    /** 우리가 늘 보는 범위 (D21 계층 폴링이 +14 까지 묻는다). 매장은 창을 밝히지 않는다 — 위 설명 */
    const val OPEN_DAYS = 15

    val BRANCHES = listOf(
        MurderParkerBranch("S9", "홍대 1호점", "murderparker-hongdae1", "_홍대1호점"),
        MurderParkerBranch("S13", "WOW 홍대점", "murderparker-wow-hongdae", "_WOW홍대점"),
        // 건대 (2026-10-22) — 꼬리표가 지점 이름과 다르다: 드롭다운은 "건대1호점" 인데 테마 꼬리표는 "_건대점", 2호점은 공백이 든 "_건대 2호점"
        MurderParkerBranch("S6", "건대 1호점", "murderparker-konkuk1", "_건대점"),
        MurderParkerBranch("S8", "건대 2호점", "murderparker-konkuk2", "_건대 2호점"),
    )
}

class MurderParkerCrawlException(message: String) : RuntimeException(message)

@Component
class MurderParkerCrawler(private val rateLimiter: HostRateLimiter) {

    private val client by lazy { CrawlHttp.client("http://${MurderParker.HOST}") }

    fun fetch(branch: MurderParkerBranch, date: LocalDate): DaySchedule = parse(branch, date, request(branch, date))

    private fun request(branch: MurderParkerBranch, date: LocalDate): String {
        val bytes = rateLimiter.throttled(MurderParker.HOST) {
            val form = LinkedMultiValueMap<String, String>().apply {
                add("JIJEM", branch.jijem)
                add("D_ROOM", "")
                add("H_Date", date.toString())
            }
            client.post()
                .uri("/sub_02/sub02_1.html")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(ByteArray::class.java)
        } ?: ByteArray(0)
        // 본문은 cp949 인데 헤더에 charset 이 없다 — 코드케이 · 싸인과 같다
        return String(bytes, CP949)
    }

    /** 테스트가 픽스처로 부른다 */
    fun parse(branch: MurderParkerBranch, date: LocalDate, html: String): DaySchedule {
        val shown = H_DATE.find(html)?.groupValues?.get(1)
        if (shown != date.toString()) throw MurderParkerCrawlException("요청한 날짜와 렌더된 날짜가 다름: ${branch.branchName} 요청=$date 응답=$shown")

        // 테마 코드 표 — 서버가 지점마다 만들어 준다. 지점 번호 · 꼬리표가 둘 다 맞아야 그 지점 응답이다
        val rooms = ROOM_OPTION.findAll(html).map { Triple(it.groupValues[1], it.groupValues[2], it.groupValues[3].trim()) }.toList()
        if (rooms.isEmpty()) throw MurderParkerCrawlException("테마 목록이 없다 — 없는 지점이거나 마크업이 바뀌었다: ${branch.branchName} $date")
        val stray = rooms.filter { (jijem, _, name) -> jijem != branch.jijem || !name.endsWith(branch.suffix) }
        if (stray.isNotEmpty()) throw MurderParkerCrawlException("다른 지점 응답이다: ${branch.branchName}(${branch.jijem}) — $stray")
        val codeByName = rooms.associate { (_, code, name) -> name to code }

        val themes = html.split(BLOCK).drop(1).map { block -> theme(branch, date, block, codeByName) }
        if (themes.isEmpty() || themes.all { it.slots.isEmpty() }) {
            throw MurderParkerCrawlException("회차가 하나도 없다 — 마크업이 바뀌었다: ${branch.branchName} $date")
        }
        return DaySchedule(branch.storeRef, date, null, MurderParker.OPEN_DAYS, themes)
    }

    private fun theme(branch: MurderParkerBranch, date: LocalDate, block: String, codeByName: Map<String, String>): ThemeSchedule {
        val fullName = TITLE.find(block)?.groupValues?.get(1)?.trim()
            ?: throw MurderParkerCrawlException("테마 이름이 없다: ${branch.branchName} $date")
        val code = codeByName[fullName] ?: throw MurderParkerCrawlException("테마 목록에 없는 이름: '$fullName' ${branch.branchName} $date")

        val slots = SLOT.findAll(block).map { m ->
            val href = m.groupValues[1]
            val time = runCatching { LocalTime.parse(m.groupValues[2].padStart(5, '0')) }.getOrNull()
                ?: throw MurderParkerCrawlException("시각을 못 읽었다: $fullName $date '${m.groupValues[2]}'")
            val available = when {
                href == "#" -> false
                href.startsWith("/sub_02/sub02_2.html?") -> {
                    val expected = listOf("JIJEM_CODE=${branch.jijem}&", "CHOIS_DATE=$date&", "ROOM_CODE=$code&", "ROOM_TIME=${m.groupValues[2]}&")
                    if (expected.any { it !in "$href&" }) throw MurderParkerCrawlException("예약 링크의 지점·날짜·테마·시각이 다르다: $fullName $date — $href")
                    true
                }
                else -> throw MurderParkerCrawlException("모르는 회차 링크: $fullName $date — $href")
            }
            Slot(time, available)
        }.toList()

        // 이름에서 지점 꼬리표를 떼고, 끝에 붙은 "60분" 은 소요시간으로 옮긴다 — "홈그라운드(Home Ground) 60분_WOW홍대점" → "홈그라운드(Home Ground)".
        // 괄호 안 표기("파커4(80분 Premium)" · 건대)는 이름에 남기고 소요시간만 읽는다 — 괄호째가 매장이 부르는 이름이다
        val base = fullName.removeSuffix(branch.suffix).trim()
        val minutes = TRAILING_MINUTES.find(base)
        val inParens = if (minutes == null) PAREN_MINUTES.find(base) else null
        return ThemeSchedule(
            externalId = code,
            themeName = minutes?.let { base.removeRange(it.range).trim() } ?: base,
            posterUrl = POSTER.find(block)?.groupValues?.get(1)?.let { "http://${MurderParker.HOST}$it" },
            genre = null,
            capacity = CAPACITY.find(block)?.groupValues?.get(1),
            runningMinutes = (minutes ?: inParens)?.groupValues?.get(1)?.toInt(),
            horrorLevel = null,
            difficulty = null,
            slots = slots.sortedBy { it.time },
        )
    }

    companion object {
        private val CP949: Charset = Charset.forName("MS949")
        private val H_DATE = Regex("""name="H_Date" value="([0-9-]{10})"""")
        private val ROOM_OPTION = Regex("""<OPTION value='sub02_1\.html\?JIJEM=(S[0-9]+)&D_ROOM=([A-Z])'[^>]*>([^<]*)""")
        private const val BLOCK = """<div class="reservTime">"""
        private val TITLE = Regex("""<h3[^>]*>\s*([^<]*?)\s*</h3>""")
        private val SLOT = Regex("""<a href="([^"]*)"\s*/>\s*<li[^>]*>\s*<span class="time"[^>]*>\s*([0-9]{1,2}:[0-9]{2})\s*</span>""")
        private val TRAILING_MINUTES = Regex("""\s*([0-9]{2,3})분$""")
        private val PAREN_MINUTES = Regex("""\(([0-9]{2,3})분[^)]*\)""")
        private val CAPACITY = Regex("""적정인원\s*:\s*<b[^>]*>\s*([0-9]+\s*~\s*[0-9]+명)""")
        private val POSTER = Regex("""<img src="(/upload_file/room/[^"]+)"""")
    }
}

/** 지점 × 날짜 = 요청 하나 (D15). 두 지점이 한 호스트라 한 줄로 선다 (D13) */
@Component
class MurderParkerAdapter(private val crawler: MurderParkerCrawler) : StoreAdapter {

    override val host = MurderParker.HOST
    override val brand = MurderParker.BRAND
    override val branches = MurderParker.BRANCHES.map { it.storeRef }

    override fun plan(dates: List<LocalDate>): List<FetchUnit> =
        MurderParker.BRANCHES.flatMap { branch ->
            dates.openWithin(MurderParker.OPEN_DAYS).map { date ->
                FetchUnit("${MurderParker.BRAND} ${branch.branchName} $date", branch.key, date) { crawler.fetch(branch, date) }
            }
        }
}
