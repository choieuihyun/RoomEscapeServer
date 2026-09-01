package com.my_dream.server.sync

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import kotlin.test.assertEquals

/**
 * 바퀴 번호가 **정확히 1씩** 오르는지 (아키텍처 D14 정정).
 *
 * 이 테스트가 왜 따로 있냐면, [PollingSchedule] 쪽 테스트가 2026-09-01 의 버그를
 * 영영 못 잡았기 때문이다 — 거기서는 번호를 `0, 1, 2, 3` 처럼 **손으로 넣는다.**
 * 번호가 균등하다는 것이 검증 대상이 아니라 **전제**였고, 그 전제가 깨져 있었다.
 *
 * 순환 계산(`sweep % 2`, `sweep % 4`)이 전부 이 전제 위에 서 있으므로
 * **여기가 무너지면 위쪽 테스트가 다 통과하면서 기능이 틀린다.**
 */
@DataJpaTest
class SweepTickerTest @Autowired constructor(private val counters: SweepCounterRepository) {

    @Test
    fun `부를 때마다 정확히 1씩 오른다`() {
        val ticker = SweepTicker(counters)

        val 번호들 = (1..5).map { ticker.next() }

        // 옛 방식(시계 나누기)은 여기서 1,2,2,3,4 처럼 뛰었다
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), 번호들)
    }

    @Test
    fun `재시작해도 0으로 안 돌아간다`() {
        repeat(3) { SweepTicker(counters).next() }

        // 새 인스턴스 = 재시작. 메모리 카운터였다면 여기서 1 이 나오고,
        // 그러면 개발 중에 자주 껐다 켤 때 같은 평일만 계속 보게 된다
        assertEquals(4L, SweepTicker(counters).next())
    }

    @Test
    fun `줄이 없어도 알아서 만든다`() {
        // 마이그레이션(V5)이 1번 줄을 넣지만 테스트는 마이그레이션을 안 쓴다.
        // 여기서 터지면 수집이 통째로 멈춘다
        counters.deleteAll()

        assertEquals(1L, SweepTicker(counters).next())
        assertEquals(1, counters.count().toInt(), "줄이 하나만 있어야 한다")
    }
}
