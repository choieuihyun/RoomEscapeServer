package com.my_dream.server.crawler.playpointlab

import com.my_dream.server.crawler.DaySchedule
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.LocalDate

class PlayPointLabCrawlException(message: String) : RuntimeException(message)

/**
 * 조회 + 검증. **여기는 아키텍처 D2(날짜·지점 대조)를 절반밖에 못 지킨다.**
 *
 * ## ⚠️ 날짜를 대조할 수 없다 — 그리고 사이트가 틀린 날짜를 거절하지 않는다
 *
 * 응답이 HTML 조각이라 **날짜가 어디에도 없다.** 그런데 사이트는 어떤 날짜를 줘도 200 이다.
 * 2026-09-04 실측:
 *
 * ```
 * date=garbage      → 슬롯 33 · 가능 0    ← 오늘(09-04) 응답과 **바이트까지 동일**(같은 MD5)
 * date=2026-01-01   → 슬롯 33 · 가능 0     과거인데 "전부 매진" 처럼 보인다
 * date=2027-12-31   → 슬롯 33 · 가능 32    먼 미래인데 "거의 다 가능" 처럼 보인다
 * ```
 *
 * **셋 다 그럴듯하고, 셋 다 틀렸다.** 이 프로젝트가 계속 경계해 온 *"틀린 요청이 그럴듯한
 * 응답으로 오는"* 함정의 가장 나쁜 형태다 — 다른 매장은 302 로 보내거나(지구별·플레이33)
 * 최소한 날짜를 되돌려 줬는데(포인트나인·다이아에그), 여기는 **되돌려 주는 값 자체가 없다.**
 *
 * ### 그래서 지키는 방법은 "틀린 날짜를 애초에 안 보내는 것" 하나뿐이다
 *
 * [PlayPointLabAdapter] 가 `openWithin(8)` 로 창 안 날짜만 만들고, [LocalDate] 를 그대로
 * 문자열로 넘기므로 `garbage` 는 나올 수 없다. **남는 위험은 사이트가 창을 줄이는 경우다** —
 * 그때는 우리가 조용히 엉뚱한 데이터를 저장하게 되고, **응답만 봐서는 알 방법이 없다.**
 *
 * > 그 최악의 경우에도 **헛알림까지 가지는 않는다.** 창 밖 날짜가 "거의 다 가능" 으로 오면
 * > `ScheduleSyncService` 의 위생 검사(D6)가 *매진이던 자리가 한꺼번에 풀렸다* 로 보고
 * > 격리한다. 안전망이 한 겹 더 있다는 뜻이지, 이 한계가 없어진다는 뜻은 아니다.
 *
 * ## 지점은 room-id 로 대조한다
 *
 * `store` 가 무시돼도 세 지점이 전부 44번 데이터로 덮이지 않게, 받은 `data-room-id` 가
 * 그 지점 것인지 본다 ([PlayPointLabBranch.roomIds]). 세 지점의 room-id 는 하나도 안 겹친다.
 */
@Component
class PlayPointLabCrawler(
    private val client: PlayPointLabClient,
    private val parser: PlayPointLabParser,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun fetch(branch: PlayPointLabBranch, date: LocalDate): DaySchedule {
        val html = client.fetchBookingList(branch, date)
        val page = parser.parse(html)

        if (page.themes.isEmpty()) {
            // 조각이 비었다. 창 밖이거나, POST 가 GET 으로 잘못 나갔거나, 사이트가 바뀌었다.
            // **예외로 끊지 않는다** — 빈 목록은 자연키 upsert 라 DB 를 지우지 않고,
            // 매 바퀴 실패를 쌓으면 진짜 고장이 묻힌다
            log.info("회차가 없다 — {} {} (창 밖이거나 응답 형식이 바뀌었다)", branch.branchName, date)
            return DaySchedule(branch.toStoreRef(), date, page.reservationRangeDays, branch.openDays, emptyList())
        }

        // 날짜는 못 보지만 지점은 본다. 이게 없으면 store 가 무시될 때 세 지점이 같은 데이터로 덮인다
        val received = parser.roomIds(html)
        val unknown = received - branch.roomIds
        if (unknown.isNotEmpty()) {
            throw PlayPointLabCrawlException(
                "이 지점 테마가 아닌 room-id 가 왔다: 요청=${branch.branchName}(${branch.id}) " +
                    "모르는 room=$unknown. store 가 무시됐거나 지점 테마가 바뀌었다 " +
                    "(PlayPointLabBranch.roomIds 확인)",
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
