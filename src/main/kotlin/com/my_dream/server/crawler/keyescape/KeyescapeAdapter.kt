package com.my_dream.server.crawler.keyescape

import com.my_dream.server.crawler.FetchUnit
import com.my_dream.server.crawler.StoreAdapter
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * 키이스케이프는 **테마마다 따로 물어야 한다.**
 * 그래서 작업 하나 = `테마 × 날짜` 이고, 한 바퀴가 `테마수 × 날짜수` 다.
 *
 * ```
 * 플레이33     4지점  ×  날짜        지점 하나를 물으면 그 지점 테마가 전부 온다
 * 키이스케이프  32테마 ×  날짜        지점이 11개인 것보다 테마 수가 더 크다
 * ```
 *
 * 전량(7일)이면 224요청 4.5분이라 5분 주기를 거의 다 먹는다.
 * 계층 폴링(D14)이 평균 3.5일치로 줄여 준다 — 112요청 2.2분.
 */
@Component
class KeyescapeAdapter(
    private val catalog: KeyescapeThemeCatalog,
    private val crawler: KeyescapeCrawler,
) : StoreAdapter {

    override val host = KeyescapeBranch.HOST
    override val brand = KeyescapeBranch.BRAND
    override val branches = KeyescapeBranch.entries.map { it.toStoreRef() }

    /**
     * ⚠️ **테마 목록이 하루 지났으면 여기서 받아 온다.** 그 바퀴는 43요청만큼 길어진다.
     * 목록 갱신도 속도 제한을 거치므로 규칙은 지켜진다.
     */
    override fun plan(dates: List<LocalDate>): List<FetchUnit> =
        KeyescapeBranch.entries.flatMap { branch ->
            catalog.themes(branch).flatMap { theme ->
                dates.map { date ->
                    FetchUnit("${branch.branchName} ${theme.name} $date") {
                        crawler.fetch(branch, theme, date)
                    }
                }
            }
        }
}
