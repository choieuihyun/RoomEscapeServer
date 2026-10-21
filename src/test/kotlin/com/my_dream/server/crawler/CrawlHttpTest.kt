package com.my_dream.server.crawler

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.File
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 매장으로 나가는 HTTP 의 공통 모양 (아키텍처 D39). 진짜 소켓을 연다 — 타임아웃과 리다이렉트는 흉내로는 확인이 안 된다.
 */
class CrawlHttpTest {

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val release = CountDownLatch(1)
    private val base get() = "http://127.0.0.1:${server.address.port}"

    @AfterEach
    fun stop() {
        release.countDown()
        server.stop(0)
    }

    @Test
    fun `답을 안 주는 서버는 타임아웃으로 끊고, 응답을 못 받은 것으로 읽힌다`() {
        // 연결은 받고 답은 안 준다 — 타임아웃이 없으면 이 요청은 영영 안 끝나고 그 호스트 줄이 선다
        server.createContext("/hang") { release.await(); it.close() }
        server.start()

        val started = System.nanoTime()
        val e = assertFailsWith<Exception> {
            CrawlHttp.client(base, readTimeout = Duration.ofMillis(300)).get().uri("/hang").retrieve().body(String::class.java)
        }
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(tookMs < 5_000, "${tookMs}ms — 타임아웃이 안 걸렸다")
        assertTrue(e.isUnreachable(), "타임아웃인데 '응답을 못 받음' 으로 안 읽힌다: $e")
    }

    @Test
    fun `리다이렉트를 따라가지 않는다 — RestClient create 와 같은 동작`() {
        // 창 밖 날짜에 302 를 주는 매장이 있다. 따라가 버리면 홈 화면을 그날 시간표로 읽으려 든다
        val followed = AtomicInteger()
        server.createContext("/moved") { ex ->
            ex.responseHeaders.add("Location", "/home")
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }
        server.createContext("/home") { ex ->
            followed.incrementAndGet()
            ex.sendResponseHeaders(200, 0)
            ex.responseBody.use { it.write("home".toByteArray()) }
        }
        server.start()

        val status = CrawlHttp.client(base).get().uri("/moved").retrieve().toBodilessEntity().statusCode.value()
        assertEquals(302, status)
        assertEquals(0, followed.get(), "302 를 따라갔다")
    }

    @Test
    fun `모든 요청이 같은 User-Agent 를 달고 나간다`() {
        // 실제로 소켓에 실린 값을 본다 — 설정만 보면 매장 Client 가 덮어쓴 것을 못 잡는다
        var seen: String? = null
        server.createContext("/ua") { ex ->
            seen = ex.requestHeaders.getFirst("User-Agent")
            ex.sendResponseHeaders(200, 0)
            ex.responseBody.use { it.write("ok".toByteArray()) }
        }
        server.start()

        CrawlHttp.client(base).get().uri("/ua").retrieve().body(String::class.java)
        assertEquals(CrawlHttp.USER_AGENT, seen)
    }

    @Test
    fun `매장 Client 는 User-Agent 를 따로 넣지 않는다`() {
        // 한 곳(CrawlHttp)에 있어야 바꿀 때 한 번에 바뀐다. 매장마다 다른 값을 끼우는 것도 이걸로 막힌다 (D43)
        val crawler = File("src/main/kotlin/com/my_dream/server/crawler")
        val offenders = crawler.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "CrawlHttp.kt" }
            .filter { f -> f.readText().contains("\"User-Agent\"") }
            .map { it.name }.toList()
        assertTrue(crawler.isDirectory, "경로가 바뀌었다 — 이 검사가 아무것도 안 보고 통과한다")
        assertEquals(emptyList(), offenders)
    }

    @Test
    fun `매장 Client 는 RestClient 를 직접 만들지 않는다`() {
        // 새 매장이 23곳 붙는다. 하나라도 RestClient.create 를 베끼면 그 매장만 타임아웃 없이 돈다
        val crawler = File("src/main/kotlin/com/my_dream/server/crawler")
        val offenders = crawler.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "CrawlHttp.kt" }
            .filter { f -> f.readLines().any { line -> !line.trimStart().startsWith("*") && !line.trimStart().startsWith("//") && "RestClient.create(" in line } }
            .map { it.name }.toList()
        assertTrue(crawler.isDirectory, "경로가 바뀌었다 — 이 검사가 아무것도 안 보고 통과한다")
        assertEquals(emptyList(), offenders, "CrawlHttp.client 를 쓴다")
    }
}
