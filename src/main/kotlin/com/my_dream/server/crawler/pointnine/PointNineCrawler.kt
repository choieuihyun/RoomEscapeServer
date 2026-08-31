package com.my_dream.server.crawler.pointnine

import com.my_dream.server.crawler.DaySchedule
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.LocalDate

class PointNineCrawlException(message: String) : RuntimeException(message)

/**
 * 조회 + 검증. **날짜와 지점을 둘 다 대조한다** (아키텍처 D2).
 *
 * ⚠️ **여기는 날짜 대조만으로 안 걸리는 함정이 있다** — 아키텍처 D20.
 * 예약 범위 밖 날짜를 물어도 `302` 가 아니라 **`200` 이 오고, 날짜도 요청한 그대로 되돌아온다.**
 * 다른 건 **회차가 통째로 없다는 것뿐**이다.
 *
 * ```
 * 2026-09-06 (오늘+6)   200 · 16,874바이트 · theme_box 4개
 * 2026-09-07 (오늘+7)   200 ·  7,751바이트 · theme_box 0개   ← 날짜는 09-07 로 잘 되돌아온다
 * ```
 *
 * **그래서 "회차 0개" 로는 범위 밖과 파서 고장을 못 가른다.** 가르는 것은 **폼이 남아 있는지**다 —
 * 범위 밖 페이지에도 날짜 입력칸과 지점 `<select>` 는 그대로 있다.
 * 그게 사라졌다면 예약 페이지 자체가 아닌 것이므로 **예외로 끊는다.**
 */
@Component
class PointNineCrawler(
    private val client: PointNineClient,
    private val parser: PointNineParser,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun fetch(branch: PointNineBranch, date: LocalDate): DaySchedule {
        val html = client.fetchReservationPage(branch, date)
        val page = parser.parse(html)

        val rendered = page.renderedDate
            ?: throw PointNineCrawlException("예약 페이지가 아닌 응답: ${branch.branchName} $date")
        if (rendered != date) {
            throw PointNineCrawlException(
                "요청한 날짜와 렌더된 날짜가 다름: ${branch.branchName} 요청=$date 응답=$rendered",
            )
        }

        // 지점이 셋이라 이게 없으면 s_zizum 이 무시돼도 세 지점이 전부 강남점 데이터로 덮인다.
        // **없으면 없는 대로 끊는다** — 지구별은 `!= null` 일 때만 봤지만, 여기서는
        // `<option selected>` 가 사라졌다는 것 자체가 폼이 깨졌다는 뜻이라 지나갈 수 없다
        val renderedBranch = parser.renderedBranchId(html)
            ?: throw PointNineCrawlException("지점 선택칸이 없다 — 예약 페이지가 아니다: ${branch.branchName} $date")
        if (renderedBranch != branch.id) {
            throw PointNineCrawlException(
                "요청한 지점과 응답 지점이 다름: 요청=${branch.branchName}(${branch.id}) 응답=$renderedBranch",
            )
        }

        if (page.themes.isEmpty()) {
            // 폼은 멀쩡한데 회차만 없다 = 예약 범위 밖이다. **실패가 아니다.**
            // 예외로 끊으면 한 바퀴마다 실패가 3건씩 쌓여 진짜 고장이 묻힌다
            // (키이스케이프 에버랜드에서 이미 겪은 것과 같은 자리다).
            //
            // 빈 목록을 그대로 흘려보내도 **DB 는 안전하다 — 저장이 자연키 upsert 라 지우는 경로가 없다.**
            // 이건 우연이 아니라 CLAUDE.md 가 못 박아 둔 규칙이고, 여기가 그 규칙이 값을 하는 자리다.
            log.info("회차가 없다 — {} {} (예약 범위 밖으로 보인다. 폼은 정상)", branch.branchName, date)
        }

        return DaySchedule(
            store = branch.toStoreRef(),
            date = date,
            reservationRangeDays = page.reservationRangeDays,
            openDays = branch.openDays,
            themes = page.themes,
        )
    }
}
