package com.my_dream.server.crawler.jigubyeol

import com.my_dream.server.crawler.FetchUnit
import com.my_dream.server.crawler.StoreAdapter
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * 지점 하나를 물으면 그 지점 테마가 전부 온다. **작업 하나 = 요청 하나**다 (D15).
 * 3지점이라 전량이면 21요청, 계층 폴링(D14)이 걸리면 한 바퀴 9요청쯤이다.
 */
@Component
class JigubyeolAdapter(private val crawler: JigubyeolCrawler) : StoreAdapter {

    override val host = JigubyeolBranch.HOST
    override val brand = JigubyeolBranch.BRAND
    override val branches = JigubyeolBranch.entries.map { it.toStoreRef() }

    override fun plan(dates: List<LocalDate>): List<FetchUnit> =
        JigubyeolBranch.entries.flatMap { branch ->
            dates.map { date ->
                FetchUnit("${branch.branchName} $date") { crawler.fetch(branch, date) }
            }
        }
}
