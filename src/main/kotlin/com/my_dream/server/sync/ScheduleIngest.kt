package com.my_dream.server.sync

import com.my_dream.server.crawler.DaySchedule
import com.my_dream.server.notify.NotificationService
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

/**
 * 받아온 하루치를 **저장하고, 알리고, 이상하면 소리내는** 자리.
 *
 * 수집 경로가 둘(주기 수집 · 손으로 한 번)이라 여기로 모았다.
 * 한쪽에만 알림을 붙이면 다른 쪽으로 수집했을 때 조용히 안 나간다.
 */
@Component
class ScheduleIngest(
    private val sync: ScheduleSyncService,
    private val notifications: NotificationService,
    @param:Value("\${collector.save-concurrency:16}") private val saveConcurrency: Int = 16,
    @param:Value("\${spring.datasource.hikari.maximum-pool-size:10}") private val dbPoolSize: Int = 24,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * **한 번에 저장하는 수의 문** (아키텍처 D40).
     *
     * 수집 스레드는 이제 호스트 수만큼 뜬다(상한 없음). 저장할 때마다 DB 커넥션을 하나씩 쥐므로,
     * 문이 없으면 호스트가 100개일 때 커넥션도 100개를 달라고 한다 — 풀(24)을 다 쓰면 그동안
     * **조회 API 가 커넥션을 못 잡고**, 풀을 키우면 Postgres 기본 상한(100)에 닿는다.
     * 문을 여기 두면 호스트가 몇 개든 수집이 쓰는 커넥션은 [saveConcurrency] 를 안 넘는다.
     *
     * 이게 속도를 깎지 않는 이유: 스레드는 대부분 상대 응답과 1.2초 간격을 기다리고, 저장은 그 사이 잠깐이다.
     * 막히면 [warnIfGateIsNarrow] 가 로그로 알린다 — 조용히 느려지지 않게.
     */
    private val saveGate = Semaphore(saveConcurrency, true)

    /** 문 앞에서 오래 기다린 경고를 너무 자주 찍지 않게. 마지막으로 찍은 시각 (나노초) */
    @Volatile private var lastNarrowWarnAt = 0L

    /**
     * 문이 풀보다 크거나 같으면 저장이 커넥션을 다 쥐어 조회 API 가 기다린다. 증상이 "가끔 느리다" 뿐이라
     * 기동할 때 크게 말해 둔다. 막지는 않는다 — 느려질 뿐 틀린 값을 저장하지는 않는다
     * (키 없는 FCM 은 조용히 안 가므로 그쪽은 막는다. 실패 방향이 다르다)
     */
    @PostConstruct
    fun warnIfPoolTooSmall() {
        if (dbPoolSize <= saveConcurrency) {
            log.warn(
                "커넥션 풀({})이 저장 문({})보다 크지 않다 — 수집이 저장하는 동안 조회 API 가 커넥션을 못 잡는다. " +
                    "DB_POOL_SIZE 를 올리거나 collector.save-concurrency 를 내릴 것",
                dbPoolSize, saveConcurrency,
            )
        }
    }

    /**
     * 지점마다 하나씩 두는 잠금. 지점 수(수십 개)만큼만 생기고 더 자라지 않는다.
     */
    private val storeLocks = ConcurrentHashMap<String, Any>()

    /**
     * **같은 지점의 저장과 알림 판정은 한 번에 하나만 한다** (아키텍처 D27).
     *
     * 전체 바퀴만 돌 때는 필요 없었다 — 같은 지점의 요청은 한 호스트 줄에 서서 차례로 오기 때문이다.
     * 감시 빠른 확인이 **다른 스레드**에서 같은 지점·같은 날짜를 가져오면서 겹칠 수 있게 됐다.
     * [HostRateLimiter] 는 HTTP 요청만 줄 세우고 그 뒤의 저장은 모른다. 겹치면:
     *
     * - 둘 다 "아까는 매진" 을 읽고 둘 다 전이로 판정한다 → **알림이 두 번 간다**
     *   (쿨다운은 "읽고 나서 보낸다" 라 동시에 읽으면 둘 다 통과한다)
     * - 처음 보는 회차를 둘 다 넣으려다 유니크 제약에 걸려 한쪽이 실패한다
     *
     * 알림까지 잠금 안에 두는 이유가 첫째 항목이다. 저장만 잠그면 판정이 다시 겹친다.
     */
    fun ingest(day: DaySchedule): SyncResult =
        synchronized(storeLocks.computeIfAbsent(day.store.key) { Any() }) {
            // 지점 잠금 **안에서** 문을 받는다 — 문을 쥔 채 남의 지점 잠금을 기다리는 일이 없게
            val waitedAt = System.nanoTime()
            saveGate.acquire()
            try {
                warnIfGateIsNarrow((System.nanoTime() - waitedAt) / 1_000_000)
                ingestLocked(day)
            } finally {
                saveGate.release()
            }
        }

    /** 문 앞에서 [NARROW_WAIT_MS] 넘게 기다렸으면 문이 좁다는 뜻이다. 1분에 한 번만 찍는다 */
    private fun warnIfGateIsNarrow(waitedMs: Long) {
        if (waitedMs < NARROW_WAIT_MS) return
        val now = System.nanoTime()
        if (now - lastNarrowWarnAt < 60_000_000_000L) return
        lastNarrowWarnAt = now
        log.warn(
            "저장 문 앞에서 {}ms 기다렸다 — 동시에 저장하려는 호스트가 문({})보다 많다. " +
                "자주 뜨면 collector.save-concurrency 와 DB_POOL_SIZE 를 같이 올린다 (풀 > 문)",
            waitedMs, saveConcurrency,
        )
    }

    private fun ingestLocked(day: DaySchedule): SyncResult {
        warnIfRangeChanged(day)
        warnIfNoThemes(day)

        val result = sync.sync(day)

        result.transitions.forEach {
            log.info("🎟️  자리 남 — {} {} {} {}", it.branchName, it.themeName, it.date, it.time)
        }
        // **저장이 끝난 뒤에** 알린다. 같은 트랜잭션 안에서 보내면 롤백돼도 알림은 못 되돌린다.
        // 알림 경로가 통째로 터져도 수집 한 바퀴는 계속 돌아야 한다 — 수집이 알림에 종속되면 안 된다
        runCatching { notifications.onTransitions(result.transitions) }
            .onFailure { log.error("알림 판정 실패 — 수집은 계속한다 : {}", it.message, it) }

        result.quarantined.forEach {
            val tail = if (it.recovered) {
                "${it.consecutive}회 연속이라 기준선 복구를 위해 이번엔 저장했다 (알림은 보내지 않음)"
            } else {
                "이번 수집분은 저장하지 않음 (${it.consecutive}회 연속)"
            }
            log.error(
                "⚠️  위생 검사 격리 — {} {} : 매진이던 {}개 중 {}개가 한 번에 풀렸다. 파서 확인 필요. {}",
                it.themeName, it.date, it.previouslyUnavailable, it.flipped, tail,
            )
        }
        return result
    }

    /**
     * 테마가 하나도 없는 지점은 **정상일 수도 있고**(지점 준비 중 · 테마를 내린 상태)
     * **파서가 깨진 신호일 수도 있다.** 조용히 0 으로 지나가면 둘을 구분할 길이 없다.
     *
     * 예외로 끊지는 않는다 — 실제로 비어 있는 지점이 있고(2026-08-26 기준 수원점),
     * 그때마다 수집 한 바퀴를 실패로 만들 이유는 없다. 대신 눈에 띄게 남긴다.
     */
    private fun warnIfNoThemes(day: DaySchedule) {
        if (day.themes.isEmpty()) {
            log.warn(
                "테마가 하나도 없다 — {} {}. 지점이 비어 있는 게 맞는지, 파서가 깨진 건지 확인이 필요하다",
                day.store.branchName, day.date,
            )
        }
    }

    /** 사이트가 예약 오픈 범위를 바꾸면 우리 순회 범위도 바뀌어야 한다. 조용히 어긋나지 않게 알린다. */
    private fun warnIfRangeChanged(day: DaySchedule) {
        val reported = day.reservationRangeDays ?: return
        if (reported != day.openDays) {
            // **전역 상수와 비교하면 안 된다.** 창은 지점마다 다르다(지구별 대구 2주 / 홍대 1주).
            // 하나로 비교하면 정상인 지점이 매 바퀴 경고를 뱉고, 그 잡음에 진짜 변화가 묻힌다
            log.warn(
                "예약 오픈 범위가 {}일로 바뀌었다 — {} {} (우리는 {}일로 보고 있다). 지점 openDays 를 조정할 것",
                reported, day.store.branchName, day.date, day.openDays,
            )
        }
    }

    companion object {
        /** 저장 하나는 수십 ms 다(추정). 1초를 기다렸으면 문 앞에 여럿이 줄 서 있었다는 뜻이다 */
        private const val NARROW_WAIT_MS = 1_000L
    }
}
