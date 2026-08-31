package com.my_dream.server.crawler.play33

import com.my_dream.server.crawler.FetchUnit
import com.my_dream.server.crawler.openWithin
import com.my_dream.server.crawler.StoreAdapter
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * 플레이33은 **지점 하나를 물으면 그 지점 테마가 전부 온다.**
 * 그래서 작업 하나 = `지점 × 날짜` 이고, 한 바퀴가 `4지점 × 날짜수` 다.
 */
@Component
class Play33Adapter(private val crawler: Play33Crawler) : StoreAdapter {

    override val host = Play33Branch.HOST
    override val brand = Play33Branch.BRAND
    override val branches = Play33Branch.entries.map { it.toStoreRef() }

    override fun plan(dates: List<LocalDate>): List<FetchUnit> =
        Play33Branch.entries.flatMap { branch ->
            dates.openWithin(branch.openDays).map { date ->
                FetchUnit("${branch.branchName} $date") { crawler.fetch(branch, date) }
            }
        }
}
