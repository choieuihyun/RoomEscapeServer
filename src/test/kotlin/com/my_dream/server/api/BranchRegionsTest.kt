package com.my_dream.server.api

import com.my_dream.server.crawler.StoreAdapter
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **지점을 더하면 지역도 같이 적었는가.**
 *
 * 어댑터 목록을 스프링에서 받는다 — enum 을 손으로 나열하면 새 매장 어댑터가 생겼을 때 이 테스트가
 * 그 매장을 모르고 통과한다. 지점을 더하고 지역을 잊으면 여기서 깨져야 한다 ([BranchRegions]).
 */
@SpringBootTest
class BranchRegionsTest @Autowired constructor(
    private val adapters: List<StoreAdapter>,
) {

    private val supported get() = adapters.flatMap { it.branches }.map { it.key }.toSet()

    @Test
    fun `지원 지점마다 지역 줄이 있다 — 모르면 null 로라도`() {
        val missing = supported.filterNot { BranchRegions.isListed(it) }
        assertTrue(missing.isEmpty(), "지역 줄이 없는 지점: $missing — BranchRegions 에 더한다(모르면 null)")
    }

    @Test
    fun `지원하지 않는 지점 줄이 남아 있지 않다`() {
        // 지점을 지우고 지역 줄만 남기면 이름이 바뀐 새 키를 빼먹어도 개수가 맞아 보인다
        assertEquals(emptySet(), BranchRegions.keys - supported)
    }

    @Test
    fun `지역 값은 앱 목록 안에 있다`() {
        // 목록 밖 값은 앱이 null 처럼 버린다 — 오타 하나로 지점이 '기타' 로 떨어진다
        val bad = supported.mapNotNull { key -> BranchRegions.of(key)?.takeIf { it !in BranchRegions.ALLOWED }?.let { key to it } }
        assertTrue(bad.isEmpty(), "앱 목록 밖 지역: $bad")
    }
}
