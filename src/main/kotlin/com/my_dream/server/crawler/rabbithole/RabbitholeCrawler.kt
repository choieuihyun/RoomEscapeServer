package com.my_dream.server.crawler.rabbithole

import com.my_dream.server.crawler.DaySchedule
import org.springframework.stereotype.Component
import java.time.LocalDate

class RabbitholeCrawlException(message: String) : RuntimeException(message)

/** 조회 + 검증. 플레이33과 같은 이유로 **렌더된 날짜를 대조한다** (아키텍처 D2). */
@Component
class RabbitholeCrawler(
    private val client: RabbitholeClient,
    private val parser: RabbitholeParser,
) {

    fun fetch(branch: RabbitholeBranch, date: LocalDate): DaySchedule {
        val page = parser.parse(client.fetchReservationPage(branch, date))

        val rendered = page.renderedDate
            ?: throw RabbitholeCrawlException("예약 페이지가 아닌 응답: ${branch.branchName} $date")
        if (rendered != date) {
            throw RabbitholeCrawlException(
                "요청한 날짜와 렌더된 날짜가 다름: ${branch.branchName} 요청=$date 응답=$rendered",
            )
        }

        return DaySchedule(
            store = branch.toStoreRef(),
            date = date,
            reservationRangeDays = page.reservationRangeDays,
            themes = page.themes,
        )
    }
}
