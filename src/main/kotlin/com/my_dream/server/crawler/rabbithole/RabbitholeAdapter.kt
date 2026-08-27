package com.my_dream.server.crawler.rabbithole

import com.my_dream.server.crawler.FetchUnit
import com.my_dream.server.crawler.StoreAdapter
import org.springframework.stereotype.Component
import java.time.LocalDate

/** 지점 하나를 물으면 테마가 전부 온다. 1지점 2테마라 전량이어도 7요청이다. */
@Component
class RabbitholeAdapter(private val crawler: RabbitholeCrawler) : StoreAdapter {

    override val host = RabbitholeBranch.HOST
    override val brand = RabbitholeBranch.BRAND
    override val branches = RabbitholeBranch.entries.map { it.toStoreRef() }

    override fun plan(dates: List<LocalDate>): List<FetchUnit> =
        RabbitholeBranch.entries.flatMap { branch ->
            dates.map { date ->
                FetchUnit("${branch.branchName} $date") { crawler.fetch(branch, date) }
            }
        }
}
