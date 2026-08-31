package com.my_dream.server.notify

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient

/**
 * `RestClient.Builder` 를 직접 만든다.
 *
 * **왜 필요한가 (2026-08-31 실제로 터진 것).** [FcmNotifier] 가 이걸 주입받는데,
 * 스프링이 알아서 줄 거라고 **가정만 하고 확인을 안 했다.** `notify.channel=fcm` 으로
 * 처음 올린 순간 기동이 이렇게 막혔다:
 *
 * ```
 * Parameter 2 of constructor in FcmNotifier required a bean of type
 * 'org.springframework.web.client.RestClient$Builder' that could not be found.
 * ```
 *
 * Spring Boot 4 는 HTTP 클라이언트 자동설정이 별도 모듈로 갈라져 있어
 * `starter-webmvc` 만으로는 이 빈이 안 생긴다. Flyway 가 `starter-flyway` 로
 * 갈라져 있던 것과 같은 이야기다.
 *
 * **의존성을 더 받는 대신 빈 하나를 직접 만든다.** 크롤러들은 이미
 * `RestClient.create(주소)` 로 각자 만들어 쓰고 있어서, 자동설정을 끌어올 이유가 이것 하나뿐이다.
 *
 * ⚠️ **왜 [FcmNotifier] 가 굳이 주입받나.** `RestClient.create(고정주소)` 로 박으면
 * 진짜 구글 서버 없이는 발송 로직을 한 줄도 확인할 수 없다. 주입해 두면 테스트가
 * `MockRestServiceServer` 를 끼워 "죽은 토큰을 지우는가" 를 검증할 수 있다.
 * **그 테스트가 통과했는데도 기동이 막혔던 이유가 바로 이 갈라짐이다** —
 * 테스트는 빌더를 손으로 만들어 넘기니 스프링 배선을 한 번도 안 거쳤다.
 */
@Configuration
class HttpClientConfig {

    /** 누가 이미 등록했으면(자동설정이 돌아오면) 그쪽을 쓴다 */
    @Bean
    @ConditionalOnMissingBean(RestClient.Builder::class)
    fun restClientBuilder(): RestClient.Builder = RestClient.builder()
}
