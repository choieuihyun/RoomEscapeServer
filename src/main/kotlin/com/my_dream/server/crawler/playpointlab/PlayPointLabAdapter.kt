package com.my_dream.server.crawler.playpointlab

import com.my_dream.server.crawler.FetchUnit
import com.my_dream.server.crawler.StoreAdapter
import com.my_dream.server.crawler.openWithin
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * 지점 하나를 물으면 그 지점 테마가 전부 온다. **작업 하나 = 요청 하나**다 (D15).
 * 부산 3지점이라 전량이면 24요청, 계층 폴링(D14)이 걸리면 한 바퀴 6요청쯤이다.
 *
 * ⚠️ **`openWithin` 이 여기서는 안전장치다.** 다른 매장에서는 "잡음을 없애는 장치" 였는데
 * (창 밖을 물어도 302 나 빈 응답이 오니까), 이 매장은 **창 밖 날짜에도 그럴듯한 데이터를
 * 준다.** 이 필터가 유일한 방어선이다 — [PlayPointLabCrawler] 주석 참고.
 */
@Component
class PlayPointLabAdapter(private val crawler: PlayPointLabCrawler) : StoreAdapter {

    override val host = PlayPointLabBranch.HOST
    override val brand = PlayPointLabBranch.BRAND
    override val branches = PlayPointLabBranch.entries.map { it.toStoreRef() }

    override fun plan(dates: List<LocalDate>): List<FetchUnit> =
        PlayPointLabBranch.entries.flatMap { branch ->
            dates.openWithin(branch.openDays).map { date ->
                FetchUnit("${branch.branchName} $date") { crawler.fetch(branch, date) }
            }
        }
}
