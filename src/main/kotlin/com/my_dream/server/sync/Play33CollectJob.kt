package com.my_dream.server.sync

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 수집을 **언제** 할지만 정한다. 실제 수집은 [StoreCollector] 가 한다.
 *
 * `collector.play33.enabled=false` 로 끄면 이 빈만 사라지고 수집 기능 자체는 남는다.
 *
 * `fixedDelay` 는 이전 실행이 **끝난 뒤부터** 간격을 재므로 수집이 겹치지 않는다.
 * (`fixedRate` 였다면 한 바퀴가 길어질 때 다음 바퀴가 밀고 들어온다.)
 */
@Component
@ConditionalOnProperty(prefix = "collector.play33", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class Play33CollectJob(private val collector: StoreCollector) {

    @Scheduled(
        fixedDelayString = "\${collector.interval-ms:300000}",
        initialDelayString = "\${collector.initial-delay-ms:10000}",
    )
    fun run() {
        collector.collectAll()
    }
}
