package com.my_dream.server.sync

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.scheduling.Trigger
import org.springframework.scheduling.TriggerContext
import org.springframework.scheduling.annotation.SchedulingConfigurer
import org.springframework.scheduling.config.ScheduledTaskRegistrar
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ThreadLocalRandom

/**
 * 감시 빠른 확인을 **언제** 할지만 정한다. 실제 일은 [WatchedSlotCollector] 가 한다 (아키텍처 D27 · D44).
 *
 * **주기 수집이 켜진 곳에서만 돈다.** 전체 바퀴와 같은 스위치(`collector.play33.enabled`)를 보므로
 * 개발 기기에서는 같이 꺼져 있다. 이것만 따로 끄려면 `COLLECTOR_WATCH_ENABLED=false`.
 *
 * [Play33CollectJob] 과 **다른 스레드**에서 돈다 ([SchedulingConfig]).
 * 스케줄러 스레드가 하나면 전체 바퀴가 도는 약 2.5분 동안 이 작업이 그 뒤에 줄을 서서 간격이 그만큼 늘어난다.
 *
 * **간격은 이전 확인이 끝난 뒤 5~7분 사이 무작위다** (사용자 결정 2026-10-22 · D44). `@Scheduled` 의 fixedDelay 는
 * 고정값만 받아서 [SchedulingConfigurer] 로 [JitteredDelay] 를 건다.
 */
@Component
@ConditionalOnExpression("\${collector.play33.enabled:false} and \${collector.watch.enabled:true}")
class WatchedSlotCollectJob(
    private val collector: WatchedSlotCollector,
    @param:Value("\${collector.watch.interval-min-ms:300000}") private val minMs: Long,
    @param:Value("\${collector.watch.interval-max-ms:420000}") private val maxMs: Long,
    @param:Value("\${collector.watch.initial-delay-ms:60000}") private val initialDelayMs: Long,
) : SchedulingConfigurer {

    private val log = LoggerFactory.getLogger(javaClass)

    /** 켜져 있다는 사실을 기동할 때 한 번 남긴다. 감시가 없으면 이 작업은 로그를 한 줄도 안 찍는다 */
    @PostConstruct
    fun announce() {
        log.info("감시 빠른 확인 켜짐 — {}~{}초 사이 무작위 (이전 확인이 끝난 뒤부터 잰다)", minMs / 1000, maxMs / 1000)
    }

    override fun configureTasks(registrar: ScheduledTaskRegistrar) {
        registrar.addTriggerTask({ collector.check() }, JitteredDelay(minMs, maxMs, initialDelayMs))
    }
}

/**
 * **이전 실행이 끝난 시각 + [minMs]~[maxMs] 사이 무작위.** 첫 실행은 등록 시각 + [initialDelayMs].
 *
 * 끝난 뒤부터 재는 것은 fixedDelay 와 같다 — 확인이 느려져도 다음 확인과 겹치지 않는다.
 *
 * ⚠️ **무작위는 부담을 줄이는 장치가 아니다.** 매장 서버에 가는 양은 평균 간격(6분)과 한 번에 보내는 수의 천장
 * (`max-units`)이 정한다. 무작위는 정해진 박자로 두드리지 않게 할 뿐이다. 그리고 **차단을 피하는 데 쓰지 않는다** —
 * 403 · 429 · 503 이면 지금처럼 그 호스트를 쉬고(D39 · `pause-minutes`), 계속 막히면 대상에서 뺀다.
 */
class JitteredDelay(
    private val minMs: Long,
    private val maxMs: Long,
    private val initialDelayMs: Long,
    private val nextLong: (Long, Long) -> Long = { from, until -> ThreadLocalRandom.current().nextLong(from, until) },
    private val clock: Clock = Clock.systemUTC(),
) : Trigger {

    init {
        require(minMs in 1..maxMs) { "간격 범위가 이상하다: $minMs~$maxMs" }
    }

    private val registeredAt: Instant = clock.instant()

    override fun nextExecution(context: TriggerContext): Instant {
        val last = context.lastCompletion() ?: return registeredAt.plusMillis(initialDelayMs)
        return last.plus(Duration.ofMillis(nextLong(minMs, maxMs + 1)))
    }
}
