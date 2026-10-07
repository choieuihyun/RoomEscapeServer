package com.my_dream.server.sync

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **감시 빠른 확인이 스프링에서 실제로 조립되는가** (아키텍처 D27).
 *
 * [WatchedSlotCollectorTest] 는 수집기를 손으로 만들어 쓴다. 그래서 `@Value` 이름이 틀렸거나
 * 켜고 끄는 조건식이 깨져 있어도 전부 통과한다 — `FcmWiringTest` 를 만든 이유와 같은 간극이다.
 * 이 길은 운영에서만 켜지므로(`COLLECTOR_ENABLED`) 여기서 안 보면 **배포한 뒤에야** 안다.
 *
 * ⚠️ **첫 실행 지연을 1시간으로 밀어 둔다.** 스위치를 켜면 전체 바퀴 작업도 같이 뜨는데,
 * 지연이 기본값(10초)이면 테스트가 도는 동안 진짜 매장 사이트를 긁는다.
 */
private const val NEVER_FIRES_1 = "collector.initial-delay-ms=3600000"
private const val NEVER_FIRES_2 = "collector.watch.initial-delay-ms=3600000"

@SpringBootTest(properties = ["collector.play33.enabled=true", NEVER_FIRES_1, NEVER_FIRES_2])
class WatchLaneWiringTest @Autowired constructor(
    private val context: ApplicationContext,
) {

    @Test
    fun `주기 수집이 켜지면 감시 빠른 확인도 같이 뜬다`() {
        assertEquals(1, context.getBeanNamesForType(WatchedSlotCollectJob::class.java).size)
    }

    @Test
    fun `스케줄러 스레드가 둘이다`() {
        // 하나면 전체 바퀴가 도는 약 2.5분 동안 빠른 확인이 그 뒤에 줄을 선다 —
        // "2분마다" 가 최대 4.5분이 되는데, 로그 어디에도 원인이 안 남는다
        assertEquals(2, context.getBean(ThreadPoolTaskScheduler::class.java).scheduledThreadPoolExecutor.corePoolSize)
    }
}

@SpringBootTest(
    properties = ["collector.play33.enabled=true", "collector.watch.enabled=false", NEVER_FIRES_1, NEVER_FIRES_2],
)
class WatchLaneKillSwitchTest @Autowired constructor(
    private val context: ApplicationContext,
) {

    @Test
    fun `이것만 따로 끌 수 있다`() {
        // 매장 쪽에서 불편해하면 코드를 안 고치고 끈다 (배포.md). 전체 바퀴는 그대로 떠 있어야 한다
        assertTrue(context.getBeanNamesForType(WatchedSlotCollectJob::class.java).isEmpty())
        assertEquals(1, context.getBeanNamesForType(Play33CollectJob::class.java).size)
    }
}

@SpringBootTest
class WatchLaneOffByDefaultTest @Autowired constructor(
    private val context: ApplicationContext,
) {

    @Test
    fun `주기 수집이 꺼진 곳에서는 뜨지 않는다`() {
        // 개발 기기에서 앱을 띄울 때마다 매장 사이트를 두드리면 안 된다
        assertTrue(context.getBeanNamesForType(WatchedSlotCollectJob::class.java).isEmpty())
    }
}
