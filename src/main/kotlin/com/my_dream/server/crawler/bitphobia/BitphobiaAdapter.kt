package com.my_dream.server.crawler.bitphobia

import com.my_dream.server.crawler.FetchUnit
import com.my_dream.server.crawler.StoreAdapter
import com.my_dream.server.crawler.openWithin
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * 지점 하나를 물으면 그 지점 테마가 전부 온다. **작업 하나 = 요청 하나**다 (D15).
 * 9지점이라 전량이면 63요청, 계층 폴링(D14)이 걸리면 한 바퀴 27요청쯤이다.
 */
@Component
class BitphobiaAdapter(private val crawler: BitphobiaCrawler) : StoreAdapter {

    override val host = BitphobiaBranch.HOST
    override val brand = BitphobiaBranch.BRAND
    override val branches = BitphobiaBranch.entries.map { it.toStoreRef() }

    override fun plan(dates: List<LocalDate>): List<FetchUnit> =
        BitphobiaBranch.entries.flatMap { branch ->
            dates.openWithin(branch.openDays).map { date ->
                FetchUnit("${branch.branchName} $date") { crawler.fetch(branch, date) }
            }
        }
}
