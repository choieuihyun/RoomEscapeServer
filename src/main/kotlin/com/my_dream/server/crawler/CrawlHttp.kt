package com.my_dream.server.crawler

import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import java.net.http.HttpClient
import java.time.Duration

/**
 * 매장 사이트로 나가는 `RestClient` 는 **전부 여기서 만든다** (아키텍처 D4 · D39).
 *
 * **왜 타임아웃이 꼭 있어야 하나.** 없으면 상대가 답을 안 주는 순간 그 호스트의 줄이 통째로 선다 —
 * [HostRateLimiter] 가 요청 내내 잠금을 쥐고 있어서다. 전체 바퀴는 `invokeAll` 이라
 * **다음 바퀴가 그 호스트 하나를 기다리고**, 감시 빠른 확인은 한 스레드로 돌아서 **통째로 멈춘다.**
 * 매장이 100곳을 넘으면 어딘가 하나가 죽어 있는 게 평소 상태다.
 *
 * **엔진은 바꾸지 않았다.** `RestClient.create(주소)` 가 고르던 것과 같은 JDK `HttpClient` 다
 * (spring-web 7.0.9 `DefaultRestClientBuilder.initRequestFactory` 확인). 리다이렉트도 그대로
 * **따라가지 않는다**(`HttpClient` 기본값 NEVER) — 창 밖 날짜에 302 를 주는 매장의 판정이 이것에 기대고 있다.
 *
 * ⚠️ 새 매장 Client 에서 `RestClient.create` 를 직접 부르지 않는다 — `CrawlHttpTest` 가 막는다.
 */
object CrawlHttp {

    /** 연결 자체가 안 되는 데 쓰는 시간. 정상이면 수십 ms 다 */
    val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)

    /**
     * 응답을 기다리는 한도. 평소 응답은 요청당 평균 0.1초 안쪽이다 — D18 실측(제로월드 135요청 · 171초)에서
     * 간격 1.2초를 뺀 계산값이다. 15초는 "정상인데 느린 날" 을 끊지 않으려는 넉넉한 여유다(근거 없는 어림).
     * 감시 빠른 확인의 "느리면 쉰다" 기준(5초)보다 길게 둔다 — 그건 **답은 온** 경우의 판단이다
     */
    val READ_TIMEOUT: Duration = Duration.ofSeconds(15)

    /**
     * 모든 매장 요청의 `User-Agent` — **일반 데스크톱 크롬 값**이다 (사용자 결정 2026-10-21 · 아키텍처 D43).
     *
     * 2026-10-21 까지는 빈 값이었다("정보를 제일 덜 남긴다"). 사용자가 "브라우저 값이 기본" 이라며 바꾸기로 했다.
     * 바꾸기 전 오라클 서버에서 19개 호스트 첫 화면을 이 값으로 1건씩 물어 403 이 없는 것을 확인했다.
     *
     * ⚠️ **이 값은 차단을 풀려고 쓰는 것이 아니다.** 막힌 매장(단편선 · IP 차단)은 그대로 제외다 —
     * 403 · 429 · 503 이면 백오프(D39)하고, 계속 막히면 대상에서 뺀다. 값을 매장마다 바꿔 끼우거나 돌려 쓰지 않는다.
     * 버전 숫자는 크롬이 올라가도 굳이 따라 올리지 않는다(실제 크롬도 `142.0.0.0` 처럼 끝자리를 0 으로 보낸다).
     *
     * 매장 Client 에서 `.header("User-Agent", …)` 로 덮어쓰지 않는다 — `CrawlHttpTest` 가 막는다. 한 곳에 있어야 한 번에 바뀐다
     */
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36"

    /** [readTimeout] 은 테스트가 15초를 기다리지 않으려고 줄여 쓴다. 운영 코드는 기본값만 쓴다 */
    fun client(baseUrl: String, readTimeout: Duration = READ_TIMEOUT): RestClient {
        val http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build()
        val factory = JdkClientHttpRequestFactory(http).apply { setReadTimeout(readTimeout) }
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory)
            .defaultHeader("User-Agent", USER_AGENT)
            .build()
    }
}

/** 예외 원인 사슬이 자기 자신을 가리키는 경우를 대비한 한도 */
private const val MAX_CAUSE_DEPTH = 10

private fun Throwable.chain() = generateSequence(this) { it.cause }.take(MAX_CAUSE_DEPTH)

/** 상대가 준 HTTP 상태 코드. 크롤러가 감싸서 던져도 찾는다. 응답이 없었으면 null */
fun Throwable.httpResponse(): RestClientResponseException? =
    chain().filterIsInstance<RestClientResponseException>().firstOrNull()

/**
 * **응답 자체를 못 받았다** — 타임아웃 · 연결 거부 · 이름 못 찾음. 스프링이 I/O 오류를 이걸로 감싼다.
 * 같은 호스트에 바로 다음 요청을 보내 봐야 같은 결과라 그 바퀴의 남은 요청을 접는 근거가 된다.
 */
fun Throwable.isUnreachable(): Boolean = chain().any { it is ResourceAccessException }

/** 로그용 한 줄 — `HTTP 429` 처럼 상태가 있으면 그것, 없으면 메시지 */
fun Throwable.describe(): String =
    httpResponse()?.let { "HTTP ${it.statusCode.value()}" } ?: message ?: javaClass.simpleName
