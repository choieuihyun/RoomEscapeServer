package com.my_dream.server.crawler.keyescape

import com.my_dream.server.crawler.DaySchedule
import com.my_dream.server.crawler.Slot
import com.my_dream.server.crawler.ThemeSchedule
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.LocalTime

/**
 * 조회 + 검증. 여기를 통과한 결과만 믿고 저장한다.
 *
 * 검증이 필요한 이유는 플레이33의 302 와 같다 — **틀린 요청이 실패로 보이지 않는다.**
 * `info_num` 과 `theme_num` 이 둘 다 작은 정수인데 ID 공간이 달라서, 섞어 보내면
 * 에러가 아니라 **엉뚱한 테마의 시간표가 그럴듯하게** 온다.
 */
@Component
class KeyescapeCrawler(private val client: KeyescapeClient) {

    fun fetch(branch: KeyescapeBranch, theme: KeyescapeTheme, date: LocalDate): DaySchedule {
        val rows = client.slots(branch, theme.themeNum, date)

        // 응답이 스스로 밝힌 테마가 우리가 물은 테마인지. 하나라도 다르면 ID 를 잘못 보낸 것이다
        rows.firstOrNull { it.themeNum != null && it.themeNum != theme.themeNum }?.let {
            throw KeyescapeCrawlException(
                "다른 테마의 응답: ${branch.branchName} ${theme.name} 요청=${theme.themeNum} 응답=${it.themeNum}",
            )
        }

        return DaySchedule(
            store = branch.toStoreRef(),
            date = date,
            // 사이트가 예약 범위를 밝히지 않는다. 모르면 null 이고, 경고도 안 뜬다
            reservationRangeDays = null,
            themes = listOf(
                ThemeSchedule(
                    externalId = theme.themeNum.toString(),
                    themeName = theme.name,
                    posterUrl = theme.posterUrl,
                    genre = theme.genre,
                    // 사이트가 인원을 안 준다
                    capacity = null,
                    runningMinutes = theme.runningMinutes,
                    horrorLevel = null,
                    difficulty = theme.difficulty,
                    slots = rows.map { it.toSlot() },
                ),
            ),
        )
    }

    /**
     * 사이트 자체 코드가 `item.enable === 'N'` 을 `disabled` 로 그린다.
     *
     * **모르는 값은 "불가" 로 본다.** 틀려도 안전한 쪽이다 —
     * 잘못 "가능" 으로 읽으면 감시 걸어둔 사람 전원에게 헛알림이 나간다.
     */
    private fun KeyescapeSlotRow.toSlot(): Slot {
        val hour = hh.toIntOrNull() ?: throw KeyescapeCrawlException("시각을 못 읽었다: $hh:$mm")
        val minute = mm.toIntOrNull() ?: throw KeyescapeCrawlException("시각을 못 읽었다: $hh:$mm")
        return Slot(time = LocalTime.of(hour, minute), available = enable == "Y")
    }
}
