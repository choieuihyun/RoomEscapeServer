package com.my_dream.server.crawler.jigubyeol

import com.my_dream.server.crawler.DaySchedule
import org.springframework.stereotype.Component
import java.time.LocalDate

class JigubyeolCrawlException(message: String) : RuntimeException(message)

/**
 * 조회 + 검증. **날짜와 지점을 둘 다 대조한다** (아키텍처 D2).
 *
 * 래빗홀은 지점이 하나라 날짜만 봤는데, 여기는 셋이다.
 * `branch` 가 무시되면 **세 지점이 전부 같은 데이터로 덮이는데** 요청도 응답도 200 이라 아무도 모른다.
 */
@Component
class JigubyeolCrawler(
    private val client: JigubyeolClient,
    private val parser: JigubyeolParser,
) {

    fun fetch(branch: JigubyeolBranch, date: LocalDate): DaySchedule {
        val html = client.fetchReservationPage(branch, date)
        val page = parser.parse(html)

        val rendered = page.renderedDate
            ?: throw JigubyeolCrawlException("예약 페이지가 아닌 응답: ${branch.branchName} $date")
        if (rendered != date) {
            throw JigubyeolCrawlException(
                "요청한 날짜와 렌더된 날짜가 다름: ${branch.branchName} 요청=$date 응답=$rendered",
            )
        }

        val renderedBranch = parser.renderedBranchId(html)
        if (renderedBranch != null && renderedBranch != branch.id) {
            throw JigubyeolCrawlException(
                "요청한 지점과 응답 지점이 다름: 요청=${branch.branchName}(${branch.id}) 응답=$renderedBranch",
            )
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
