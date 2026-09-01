package com.my_dream.server.sync

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.LockModeType
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * 수집 바퀴 번호를 들고 있는 **한 줄짜리 테이블**. [SweepTicker] 만 손댄다.
 *
 * 번호는 계속 커지기만 한다. 넘칠 걱정은 안 해도 된다 —
 * 8분에 하나씩 올려서 `Long` 을 채우려면 우주 나이보다 오래 걸린다.
 */
@Entity
@Table(name = "sweep_counter")
class SweepCounter(
    @Id val id: Short = 1,
    // 컬럼명이 `value` 가 아닌 이유: H2 예약어라 테스트에서만 쿼리가 깨진다
    @Column(name = "sweep_no", nullable = false) var value: Long = 0,
)

interface SweepCounterRepository : JpaRepository<SweepCounter, Short> {

    /**
     * **줄을 세워서 읽는다** (`SELECT … FOR UPDATE`).
     *
     * 지금은 스케줄러 스레드 하나만 부르므로 없어도 된다. 그런데 이 잠금이 없으면
     * **앱을 두 개 띄우는 순간 조용히 틀린다** — 둘이 같은 번호를 읽어 같은 날짜를 긁고,
     * 로그에는 "한 바퀴 완료" 가 두 번 찍힐 뿐 원인이 안 남는다.
     * 배포를 무중단으로 바꾸면(새 컨테이너를 띄우고 옛것을 내리는 방식) 잠깐 둘이 겹친다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    override fun findById(id: Short): java.util.Optional<SweepCounter>
}

/**
 * "지금 몇 번째 바퀴인가" 를 세는 곳. **[PollingSchedule] 과 일부러 나눠 두었다.**
 *
 * 어느 날짜를 볼지 정하는 계산([PollingSchedule.datesForSweep])은 DB 를 몰라야 테스트가 쉽다 —
 * 번호를 넣으면 날짜가 나오는 순수 함수다. 그 번호를 **어디서 얻느냐**가 이 클래스의 일이고,
 * 바로 그 자리에서 2026-09-01 의 버그가 났다.
 *
 * ### 왜 DB 인가 (아키텍처 D14 정정)
 *
 * ```
 * 옛 방식   sweep = now / interval-ms      나눗수(300초)와 실제 주기(470초)가 달라
 *                                          번호가 1~2 씩 뛰었다 → 날짜가 굶었다
 * 메모리    var sweep = 0L                 재시작하면 0. 개발 중에 자주 껐다 켜면
 *                                          같은 평일만 계속 본다
 * DB (지금) UPDATE … SET value = value+1   재시작해도 이어지고, 정확히 1씩 오른다
 * ```
 *
 * **1씩 오르는 것이 이 클래스의 전부다.** `sweep % 2`(금요일)·`sweep % 4`(평일 순환)·
 * 먼 창 순환이 전부 "번호가 1씩 오른다" 를 전제로 나머지 연산을 쓴다.
 * 그 전제가 깨져 있던 것이 버그였지, 나머지 연산이 틀린 게 아니었다.
 */
@Service
class SweepTicker(private val counters: SweepCounterRepository) {

    /**
     * 번호를 하나 올리고 **올린 값**을 준다.
     *
     * **수집 트랜잭션과 따로 논다** (`REQUIRES_NEW`). 수집이 실패해도 번호는 올라간다 —
     * 되돌리면 실패한 날짜를 영원히 다시 긁게 되고, 그동안 다른 날짜가 굶는다.
     * **한 바퀴 실패는 다음 바퀴에 회복되지만, 굶는 날짜는 스스로 회복되지 않는다.**
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun next(): Long {
        // 마이그레이션(V5)이 1번 줄을 넣어 두지만, 마이그레이션을 안 쓰는 테스트에서는 비어 있다.
        // 없으면 만든다 — 여기서 막히면 수집이 통째로 멈춘다
        val counter = counters.findById(1).orElseGet { SweepCounter(1, 0) }
        counter.value += 1
        return counters.save(counter).value
    }
}
