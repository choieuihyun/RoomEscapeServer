package com.my_dream.server.crawler.diaegg

import com.my_dream.server.crawler.DaySchedule
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.LocalDate

class DiaeggCrawlException(message: String) : RuntimeException(message)

/**
 * 조회 + 검증. **날짜와 지점을 둘 다 대조한다** (아키텍처 D2).
 *
 * 포인트나인과 같은 함정이 있다 — **범위 밖 날짜도 `302` 가 아니라 `200` 이고, 날짜도
 * 요청한 그대로 되돌아온다.** 다른 건 회차가 통째로 없다는 것뿐이다 (D20).
 *
 * ```
 * 2026-09-08 (오늘+4)   200 · 23,413바이트 · theme_box 4개
 * 2026-09-05 (오늘+1)   200 · 11,947바이트 · theme_box 0개   ← 날짜는 09-05 로 잘 되돌아온다
 * ```
 *
 * **여기는 앞쪽도 비어 있다는 게 포인트나인과 다르다.** 다른 매장은 창이 오늘부터인데
 * 이 매장만 오늘~+3 이 닫혀 있다 ([DiaeggBranch.leadDays]).
 */
@Component
class DiaeggCrawler(
    private val client: DiaeggClient,
    private val parser: DiaeggParser,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun fetch(branch: DiaeggBranch, date: LocalDate): DaySchedule {
        val html = client.fetchReservationPage(branch, date)
        val page = parser.parse(html)

        val rendered = page.renderedDate
            ?: throw DiaeggCrawlException("예약 페이지가 아닌 응답: ${branch.branchName} $date")
        if (rendered != date) {
            throw DiaeggCrawlException(
                "요청한 날짜와 렌더된 날짜가 다름: ${branch.branchName} 요청=$date 응답=$rendered",
            )
        }

        // 지점이 둘이라 이게 없으면 s_zizum 이 무시돼도 두 지점이 전부 다이아에그 데이터로 덮인다.
        // 폼의 hidden 입력이 사라졌다는 것은 예약 페이지가 아니라는 뜻이라 지나갈 수 없다
        val renderedBranch = parser.renderedBranchId(html)
            ?: throw DiaeggCrawlException("지점 값이 없다 — 예약 페이지가 아니다: ${branch.branchName} $date")
        if (renderedBranch != branch.id) {
            throw DiaeggCrawlException(
                "요청한 지점과 응답 지점이 다름: 요청=${branch.branchName}(${branch.id}) 응답=$renderedBranch",
            )
        }

        if (page.themes.isEmpty()) {
            // 폼은 멀쩡한데 회차만 없다 = 예약 범위 밖이다. **실패가 아니다.**
            // 예외로 끊으면 한 바퀴마다 실패가 쌓여 진짜 고장이 묻힌다.
            // 빈 목록을 흘려보내도 DB 는 안전하다 — 저장이 자연키 upsert 라 지우는 경로가 없다
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
