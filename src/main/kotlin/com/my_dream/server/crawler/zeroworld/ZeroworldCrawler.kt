package com.my_dream.server.crawler.zeroworld

import com.my_dream.server.crawler.DaySchedule
import com.my_dream.server.crawler.ThemeSchedule
import org.springframework.stereotype.Component
import java.time.LocalDate

class ZeroworldCrawlException(message: String) : RuntimeException(message)

/**
 * 조회 + 검증. **여기는 다른 매장에 없는 검증이 하나 더 있다** (아키텍처 D23).
 *
 * 다른 매장은 "요청한 날짜/지점이 응답에도 있나" 까지만 볼 수 있었다. 그건 *"서버에 닿았다"* 는
 * 증거지 *"내가 회차를 제대로 읽었다"* 는 증거가 아니다.
 * 제로월드는 응답 끝에 **사이트가 스스로 센 `{@}가능/전체`** 를 붙여 준다.
 */
@Component
class ZeroworldCrawler(
    private val client: ZeroworldClient,
    private val parser: ZeroworldParser,
) {

    /** 테마 하나의 하루치. **작업 하나 = 요청 하나**라 테마마다 부른다 (D15) */
    fun fetchTheme(branch: ZeroworldBranch, theme: ZeroworldTheme, date: LocalDate): DaySchedule {
        val times = parser.times(client.themeTimeList(branch, theme.themeNum, date))

        verify(branch, theme, date, times)

        return DaySchedule(
            store = branch.toStoreRef(),
            date = date,
            // 사이트가 값으로는 안 준다. 달력(act=calendar)에는 있지만 회차 응답에는 없다
            reservationRangeDays = null,
            openDays = branch.openDays,
            themes = listOf(
                ThemeSchedule(
                    externalId = theme.themeNum,
                    themeName = theme.name,
                    posterUrl = theme.posterUrl,
                    genre = theme.genre,
                    // 사이트가 인원을 안 준다
                    capacity = null,
                    runningMinutes = theme.runningMinutes,
                    // 공포도도 안 준다. 0 이 아니라 모르는 것이다
                    horrorLevel = null,
                    difficulty = theme.difficulty,
                    slots = times.slots,
                ),
            ),
        )
    }

    /**
     * **사이트가 센 숫자와 우리가 센 숫자를 맞춰 본다.**
     *
     * ```
     * {@}1/11 가능   →  가능 1개 · 전체 11개
     * ```
     *
     * 어긋나면 **우리 파서가 틀린 것**이므로 저장하지 않고 끊는다.
     *
     * - 전체가 다르다 → 회차를 흘리거나 겹쳐 세고 있다
     * - 가능이 다르다 → **`href` 판정이 틀렸다.** 이게 제일 무서운 종류다 —
     *   잘못 "가능" 으로 읽으면 감시자 전원에게 헛알림이 나간다
     *
     * 집계 자체가 없으면 응답 모양이 바뀐 것이라 역시 끊는다.
     * **조용히 통과시키면 검증이 있는 척만 하게 된다.**
     */
    private fun verify(branch: ZeroworldBranch, theme: ZeroworldTheme, date: LocalDate, times: ZeroworldTimes) {
        times.mismatch()?.let {
            throw ZeroworldCrawlException(
                "$it — ${branch.brand} ${branch.branchName} ${theme.name} $date",
            )
        }
    }
}
