package com.my_dream.server.sync

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 감시 빠른 확인을 **언제** 할지만 정한다. 실제 일은 [WatchedSlotCollector] 가 한다 (아키텍처 D27).
 *
 * **주기 수집이 켜진 곳에서만 돈다.** 전체 바퀴와 같은 스위치(`collector.play33.enabled`)를 보므로
 * 개발 기기에서는 같이 꺼져 있다. 이것만 따로 끄려면 `COLLECTOR_WATCH_ENABLED=false`.
 *
 * [Play33CollectJob] 과 **다른 스레드**에서 돈다 ([SchedulingConfig]).
 * 스케줄러 스레드가 하나면 전체 바퀴가 도는 약 2.5분 동안 이 작업이 그 뒤에 줄을 서서,
 * 2분마다 보겠다는 말이 최대 4.5분이 된다.
 */
@Component
@ConditionalOnExpression("\${collector.play33.enabled:false} and \${collector.watch.enabled:true}")
class WatchedSlotCollectJob(
    private val collector: WatchedSlotCollector,
    @param:Value("\${collector.watch.interval-ms:120000}") private val intervalMs: Long,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** 켜져 있다는 사실을 기동할 때 한 번 남긴다. 감시가 없으면 이 작업은 로그를 한 줄도 안 찍는다 */
    @PostConstruct
    fun announce() {
        log.info("감시 빠른 확인 켜짐 — {}초마다 (이전 확인이 끝난 뒤부터 잰다)", intervalMs / 1000)
    }

    @Scheduled(
        fixedDelayString = "\${collector.watch.interval-ms:120000}",
        initialDelayString = "\${collector.watch.initial-delay-ms:60000}",
    )
    fun run() {
        collector.check()
    }
}
