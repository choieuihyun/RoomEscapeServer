package com.my_dream.server.crawler.play33

import com.my_dream.server.crawler.DaySchedule
import org.springframework.stereotype.Component
import java.time.LocalDate

class Play33CrawlException(message: String) : RuntimeException(message)

/**
 * 조회 + 검증. 여기를 통과한 결과만 믿고 저장한다.
 *
 * 검증이 필요한 이유: 예약 범위(기본 7일) 밖의 날짜를 요청하면 사이트가 홈으로 302 시킨다.
 * 그대로 파싱하면 "그 날 슬롯이 전부 사라졌다"로 읽혀서, 저장 단계에서 멀쩡한 데이터를 지우게 된다.
 * 빈 결과와 조회 실패를 구분하려고 예외로 끊는다.
 */
@Component
class Play33Crawler(
    private val client: Play33Client,
    private val parser: Play33Parser,
) {

    fun fetch(branch: Play33Branch, date: LocalDate): DaySchedule {
        val page = parser.parse(client.fetchReservationPage(branch, date))

        val renderedDate = page.renderedDate
            ?: throw Play33CrawlException(
                "예약 페이지가 아닌 응답: ${branch.branchName} $date (예약 범위 밖 날짜는 홈으로 302 된다)",
            )
        if (renderedDate != date) {
            throw Play33CrawlException(
                "요청한 날짜와 렌더된 날짜가 다름: ${branch.branchName} 요청=$date 응답=$renderedDate",
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
