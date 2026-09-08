package com.my_dream.server.crawler.diaegg

import com.my_dream.server.crawler.FetchUnit
import com.my_dream.server.crawler.StoreAdapter
import com.my_dream.server.crawler.openWithin
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * 지점 하나를 물으면 그 지점 테마가 전부 온다. **작업 하나 = 요청 하나**다 (D15).
 * 2지점이라 전량이면 14요청, 계층 폴링(D14)이 걸리면 한 바퀴 4요청쯤이다.
 *
 * **`leadDays` 를 넘기는 유일한 어댑터다** — 이 매장만 창이 오늘부터 시작하지 않는다.
 * 안 넘기면 매 바퀴 오늘~+3 을 헛되이 물어 `warnIfNoThemes` 경고가 계속 찍힌다.
 */
@Component
class DiaeggAdapter(private val crawler: DiaeggCrawler) : StoreAdapter {

    override val host = DiaeggBranch.HOST
    override val brand = DiaeggBranch.BRAND
    override val branches = DiaeggBranch.entries.map { it.toStoreRef() }

    override fun plan(dates: List<LocalDate>): List<FetchUnit> =
        DiaeggBranch.entries.flatMap { branch ->
            dates.openWithin(branch.openDays, leadDays = branch.leadDays).map { date ->
                FetchUnit("${branch.branchName} $date") { crawler.fetch(branch, date) }
            }
        }
}
