package com.my_dream.server.crawler.zeroworld

import com.my_dream.server.crawler.Slot
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.LocalTime

/** 테마 목록 한 줄. 회차는 따로 받아야 해서 여기 없다 */
data class ZeroworldTheme(
    val themeNum: String,
    val name: String,
    val genre: String?,
    val difficulty: Double?,
    val runningMinutes: Int?,
    val posterUrl: String?,
)

/**
 * 회차 응답. **[available]/[total] 은 사이트가 스스로 센 값이다** — 우리가 센 게 아니다.
 *
 * 응답 끝에 `{@}8/8 가능` 으로 붙어 온다. **이걸 우리 파싱 결과와 대조한다** (D23).
 */
data class ZeroworldTimes(
    val slots: List<Slot>,
    val available: Int?,
    val total: Int?,
) {
    /**
     * **사이트가 센 숫자와 우리가 센 숫자가 맞나.** 맞으면 null, 아니면 어긋난 이유.
     *
     * 크롤러가 이걸 보고 예외로 끊는다 (D23). **순수 함수로 뺀 이유는 테스트다** —
     * 진짜 응답 없이도 "어긋나면 잡아내는가" 를 확인할 수 있어야 한다.
     */
    fun mismatch(): String? {
        // 지금까지 모든 응답에 있었다. 없어졌으면 응답 모양이 바뀐 것이다.
        // 조용히 통과시키면 검증이 있는 척만 하게 된다
        if (total == null) return "집계 `{@}n/m` 이 없다 — 응답 모양이 바뀌었다"
        if (slots.size != total) return "회차 수가 다르다: 우리=${slots.size} 사이트=$total"
        val ours = slots.count { it.available }
        // 가능 수가 어긋나는 것이 제일 무섭다 — `href` 판정이 틀렸다는 뜻이고,
        // 잘못 "가능" 으로 읽으면 감시자 전원에게 헛알림이 나간다
        if (available != null && ours != available) {
            return "예약가능 수가 다르다: 우리=$ours 사이트=$available"
        }
        return null
    }
}

@Component
class ZeroworldParser {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * `act=theme_list` 응답 조각.
     *
     * ```html
     * <a href="javascript:fun_theme_select('36','0')" class="choice-themes__item">
     *   <p class="choice-themes__name">사랑...하는...감?</p>
     *   <p class="choice-themes__genre">병맛</p>
     *   <div class='level__bullet full'></div>×3 <div class='level__bullet half'></div> <div class='level__bullet'></div>
     *   <p class="choice-themes__playtime"><img …> 60 </p>
     * ```
     */
    fun themes(html: String): List<ZeroworldTheme> =
        Jsoup.parse(html, BASE_URI).select("a.choice-themes__item").mapNotNull { item ->
            val num = THEME_NUM.find(item.attr("href"))?.groupValues?.get(1) ?: return@mapNotNull null
            val name = item.selectFirst("p.choice-themes__name")?.text()?.trim().orEmpty()
            if (name.isBlank()) return@mapNotNull null
            ZeroworldTheme(
                themeNum = num,
                name = name,
                genre = item.selectFirst("p.choice-themes__genre")?.text()?.trim()?.ifBlank { null },
                difficulty = item.difficulty(),
                runningMinutes = item.selectFirst("p.choice-themes__playtime")?.text()?.digitsOrNull(),
                posterUrl = item.selectFirst("img.choice-themes__thumb, .choice-themes__img img")
                    ?.absUrl("src")?.ifBlank { null },
            )
        }

    /**
     * 난이도가 **채워진 칸 수**다. `full` 은 한 칸, `half` 는 반 칸.
     *
     * ```
     * full full full half (빈칸)  →  3 + 0.5 = 3.5
     * ```
     *
     * **네 번째 표현 방식이다.** 래빗홀·플레이33은 `size35` 클래스(D10), 지구별은 표의 글자,
     * 포인트나인·비트포비아는 아이콘 개수, 여기는 `full`/`half` 세기 —
     * **네 매장이 네 가지다.** 앞의 규칙을 옮겨 붙일 자리가 하나도 없다.
     *
     * **만점(5칸)이 눈에 보이는데도 0~1 로 환산하지 않는다.** 다른 매장이 전부 원값이라
     * 혼자 정규화하면 화면에서 테마끼리 비교가 깨진다.
     */
    private fun Element.difficulty(): Double? {
        val bullets = select("div.level__bullet")
        if (bullets.isEmpty()) return null
        val score = bullets.sumOf {
            when {
                it.hasClass("full") -> 1.0
                it.hasClass("half") -> 0.5
                else -> 0.0
            }
        }
        return score
    }

    /**
     * `act=theme_time_list` 응답 조각.
     *
     * ```html
     * <a class="choice-time__time disable">10:30</a>                                       매진
     * <a class="choice-time__time" href="javascript:fun_theme_time_select('144','1')">11:35</a>  가능
     * {@}1/11 가능
     * ```
     *
     * **판정은 `href` 다.** `disable` 클래스도 같이 붙지만, 예약 폼으로 가는 링크는
     * 실제로 예약이 될 때만 생긴다 — 클래스 이름은 디자인 사정으로 바뀐다.
     *
     * ⚠️ **`{@}n/m` 을 놓치지 말 것.** 이건 사이트가 스스로 센 `가능/전체` 다.
     * 이 값이 있어서 **우리 판정이 맞는지 진짜 응답으로 확인할 수 있다** — 다른 매장에는 없는 것이다.
     */
    fun times(html: String): ZeroworldTimes {
        val counts = COUNTS.find(html)
        // `{@}5/9 가능` 은 `</a>` 뒤에 붙는 **맨 텍스트**라 `a.choice-time__time` 에 안 걸린다.
        // 잘라내는 코드를 뒀다가 지웠다 — 없어도 결과가 같은데, 없는 문제를 막는 코드는
        // 테스트로 고정할 수가 없어서 나중에 아무도 왜 있는지 모르게 된다
        val slots = Jsoup.parse(html).select("a.choice-time__time").mapNotNull { a ->
            val time = a.ownText().trim().toLocalTimeOrNull() ?: return@mapNotNull null
            Slot(time = time, available = a.hasAttr("href") && a.attr("href").isNotBlank())
        }
        if (counts == null) {
            // 지금까지 모든 응답에 있었다. 없어졌다면 응답 모양이 바뀐 것이므로 크롤러가 끊는다
            log.warn("집계 `{@}n/m` 이 없다. 응답 모양이 바뀌었는지 확인 필요")
        }
        return ZeroworldTimes(
            slots = slots,
            available = counts?.groupValues?.get(1)?.toIntOrNull(),
            total = counts?.groupValues?.get(2)?.toIntOrNull(),
        )
    }

    private fun String.digitsOrNull(): Int? = filter { it.isDigit() }.toIntOrNull()

    private fun String.toLocalTimeOrNull(): LocalTime? = runCatching { LocalTime.parse(trim()) }.getOrNull()

    companion object {
        private const val BASE_URI = "https://" + ZeroworldBranch.HOST + "/layout/res/"
        private val THEME_NUM = Regex("fun_theme_select\\('(\\d+)'")

        /** 응답 맨 끝의 `{@}8/8 가능` */
        private val COUNTS = Regex("\\{@}(\\d+)/(\\d+)")
    }
}
