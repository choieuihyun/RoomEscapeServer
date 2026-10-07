package com.my_dream.server.sync

import org.springframework.boot.task.ThreadPoolTaskSchedulerCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 스케줄러 스레드 수. **주기 작업이 둘이라 둘이다** — [Play33CollectJob] 과 [WatchedSlotCollectJob].
 *
 * 스프링 기본값은 1이다. 하나면 전체 바퀴가 도는 약 2.5분 동안 감시 빠른 확인이 그 뒤에 줄을 서서
 * "2분마다" 가 최대 4.5분이 된다 — 틀린 값을 저장하지는 않지만, 느려진 이유가 로그 어디에도 안 남는다.
 *
 * **설정 파일이 아니라 코드에 둔 이유:** 이 값은 운영하면서 돌리는 손잡이가 아니라
 * "작업이 몇 개인가" 를 따라가는 값이다. 그리고 테스트는 자기 `application.properties` 를 따로 써서
 * 운영 설정 파일에 적은 값을 **테스트가 볼 수 없다** — 2026-10-07 에 처음엔 설정 파일에 적었다가
 * `WatchLaneWiringTest` 가 1을 읽는 것을 보고 옮겼다. 여기 두면 어느 환경에서든 같은 값이고 테스트가 잰다.
 *
 * 같은 호스트로 가는 요청은 스레드가 몇이든 한 줄이다. 그건 `HostRateLimiter` 가 지킨다 (D13).
 * 주기 작업을 하나 더 만들면 이 숫자도 같이 올린다.
 */
@Configuration
class SchedulingConfig {

    @Bean
    fun schedulerThreads() = ThreadPoolTaskSchedulerCustomizer { it.poolSize = SCHEDULED_JOBS }

    companion object {
        const val SCHEDULED_JOBS = 2
    }
}
