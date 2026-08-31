package com.my_dream.server.crawler.bitphobia

import com.my_dream.server.crawler.DaySchedule
import com.my_dream.server.crawler.isLastOpenDay
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.LocalDate

class BitphobiaCrawlException(message: String) : RuntimeException(message)

/**
 * 조회 + 검증. 날짜와 지점을 둘 다 대조하고(D2), **회차 0개를 두 가지로 가른다**(D22).
 */
@Component
class BitphobiaCrawler(
    private val client: BitphobiaClient,
    private val parser: BitphobiaParser,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun fetch(branch: BitphobiaBranch, date: LocalDate, today: LocalDate = LocalDate.now()): DaySchedule {
        val html = client.fetchReservationPage(branch, date)
        val page = parser.parse(html)

        val rendered = page.renderedDate
            ?: throw BitphobiaCrawlException("예약 페이지가 아닌 응답: ${branch.branchName} $date")
        if (rendered != date) {
            throw BitphobiaCrawlException(
                "요청한 날짜와 렌더된 날짜가 다름: ${branch.branchName} 요청=$date 응답=$rendered",
            )
        }

        val renderedBranch = parser.renderedBranchId(html)
            ?: throw BitphobiaCrawlException("지점 선택칸이 없다 — 예약 페이지가 아니다: ${branch.branchName} $date")
        if (renderedBranch != branch.id) {
            throw BitphobiaCrawlException(
                "요청한 지점과 응답 지점이 다름: 요청=${branch.branchName}(${branch.id}) 응답=$renderedBranch",
            )
        }

        warnIfUnexpectedlyEmpty(branch, date, today, page.themes.sumOf { it.slots.size })

        return DaySchedule(
            store = branch.toStoreRef(),
            date = date,
            reservationRangeDays = page.reservationRangeDays,
            openDays = branch.openDays,
            themes = page.themes,
        )
    }

    /**
     * **회차가 0개인 것이 정상일 때와 아닐 때를 가른다** (아키텍처 D22).
     *
     * 이 사이트는 **매일 하루씩** 예약을 연다. 그래서 창의 **마지막 날짜**는 그날 오픈 시각이
     * 지나기 전까지 `200` · 날짜 정상 · 지점 정상 · **테마도 정상인데 회차만 0개**로 온다.
     * 포인트나인처럼 테마 유무로 가를 수가 없다 — 테마는 멀쩡히 있다.
     *
     * ```
     * 마지막 날짜라서 0개    매일 정상적으로 생긴다        → 조용히 넘어간다
     * 그 밖의 날짜가 0개     오픈 규칙이 바뀌었거나 파서 고장 → 경고
     * ```
     *
     * **경고를 무조건 달면 안 되는 이유** — 9지점 × 매 바퀴만큼 매일 쏟아진다.
     * 잡음이 쌓이면 진짜 고장이 묻힌다. 키이스케이프 에버랜드에서 이미 겪었다.
     *
     * **예외로 끊지 않는 이유** — 저장이 자연키 upsert 뿐이라 빈 회차가 지우는 일이 없고,
     * 오픈 뒤 바퀴에서 행이 새로 생긴다. 새로 생기는 것은 전이가 아니라 헛알림도 안 난다.
     */
    private fun warnIfUnexpectedlyEmpty(branch: BitphobiaBranch, date: LocalDate, today: LocalDate, slots: Int) {
        if (slots > 0) return
        val lastOpenDay = today.plusDays((branch.openDays - 1).toLong())
        if (date.isLastOpenDay(branch.openDays, today)) {
            log.debug("회차 0개 — {} {} (창의 마지막 날. 오픈 시각 전으로 보인다)", branch.branchName, date)
        } else {
            log.warn(
                "회차가 0개다 — {} {}. 창의 마지막 날({})이 아닌데 비었다. " +
                    "오픈 규칙이 바뀌었거나 파서가 깨졌다. 확인 필요",
                branch.branchName, date, lastOpenDay,
            )
        }
    }
}
