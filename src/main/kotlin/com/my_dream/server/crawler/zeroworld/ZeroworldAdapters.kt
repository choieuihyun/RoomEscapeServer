package com.my_dream.server.crawler.zeroworld

import com.my_dream.server.crawler.FetchUnit
import com.my_dream.server.crawler.StoreAdapter
import com.my_dream.server.crawler.openWithin
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * **어댑터가 둘인데 호스트는 하나다.**
 *
 * `제로월드` 와 `제로월드 다이브` 는 화면에 다른 브랜드로 나오고 [StoreAdapter.brand] 는
 * "사람이 읽는 이름" 이라, 하나로 합치면 그 필드가 거짓말을 한다.
 *
 * **합치지 않아도 속도 규칙은 안 깨진다** — `HostRateLimiter` 도 `StoreCollector` 의 병렬 묶음도
 * **호스트로** 가른다(D13). `host` 가 같으니 둘은 같은 줄에 선다.
 * 브랜드를 나누는 것과 요청을 나누는 것은 다른 이야기다.
 */
abstract class ZeroworldAdapterBase(
    private val catalog: ZeroworldThemeCatalog,
    private val crawler: ZeroworldCrawler,
    private val subject: String,
) : StoreAdapter {

    override val host = ZeroworldBranch.HOST
    override val branches = ZeroworldBranch.of(subject).map { it.toStoreRef() }

    /**
     * **테마마다 요청 하나**라 여기서 곱해진다 (키이스케이프와 같은 성질).
     * 27테마 × 날짜라 이 매장이 지금 제일 비싸다 — 한 바퀴 4일치면 108요청 130초다.
     *
     * 테마 목록은 [ZeroworldThemeCatalog] 가 하루 한 번만 받으므로 여기서는 요청이 아니다.
     */
    override fun plan(dates: List<LocalDate>): List<FetchUnit> =
        ZeroworldBranch.of(subject).flatMap { branch ->
            val themes = catalog.themes(branch)
            dates.openWithin(branch.openDays).flatMap { date ->
                themes.map { theme ->
                    FetchUnit("${branch.branchName} ${theme.name} $date") {
                        crawler.fetchTheme(branch, theme, date)
                    }
                }
            }
        }
}

@Component
class ZeroworldAdapter(catalog: ZeroworldThemeCatalog, crawler: ZeroworldCrawler) :
    ZeroworldAdapterBase(catalog, crawler, "A") {
    override val brand = ZeroworldBranch.ZEROWORLD
}

@Component
class ZeroworldDiveAdapter(catalog: ZeroworldThemeCatalog, crawler: ZeroworldCrawler) :
    ZeroworldAdapterBase(catalog, crawler, "B") {
    override val brand = ZeroworldBranch.ZEROWORLD_DIVE
}
