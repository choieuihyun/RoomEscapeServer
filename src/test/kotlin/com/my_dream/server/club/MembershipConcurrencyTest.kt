package com.my_dream.server.club

import com.my_dream.server.domain.ClubMember
import com.my_dream.server.domain.ClubMemberRepository
import com.my_dream.server.domain.MemberRole
import com.my_dream.server.domain.MemberStatus
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

/**
 * **동시에 눌렀을 때** — 진짜 Postgres 의 행 잠금으로 확인한다 (H2 는 잠금 동작이 다르다).
 *
 * 두 경우 다 "하나씩 하면 맞는데 동시에 하면 틀리는" 종류라 단위 테스트로는 안 보인다.
 * 잠금을 빼면 **항상** 실패하지는 않는다 — 운이 좋으면 지나간다. 그래서 여러 번 돌린다.
 */
@SpringBootTest(
    properties = [
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
    ],
)
@Testcontainers(disabledWithoutDocker = true)
class MembershipConcurrencyTest @Autowired constructor(
    private val service: MembershipService,
    private val members: ClubMemberRepository,
) {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val pg = PostgreSQLContainer("postgres:17")

        const val ROUNDS = 10
    }

    private val pool = Executors.newFixedThreadPool(2)

    @BeforeEach
    fun clean() = members.deleteAll()

    /** 두 작업을 **같은 순간에** 출발시키고, 각자 성공했는지 실패 코드를 돌려준다 */
    private fun race(a: () -> Unit, b: () -> Unit): List<String> {
        val gate = CyclicBarrier(2)
        fun wrap(f: () -> Unit) = Callable {
            gate.await(5, TimeUnit.SECONDS)
            try {
                f(); "OK"
            } catch (e: ClubError) {
                e.code
            }
        }
        return listOf(pool.submit(wrap(a)), pool.submit(wrap(b))).map { it.get(10, TimeUnit.SECONDS) }
    }

    @Test
    fun `두 관리자가 동시에 서로를 내려도 관리자는 0명이 안 된다`() {
        repeat(ROUNDS) {
            members.deleteAll()
            members.save(ClubMember("A", "에이", MemberRole.ADMIN, MemberStatus.APPROVED, Instant.now()))
            members.save(ClubMember("B", "비", MemberRole.ADMIN, MemberStatus.APPROVED, Instant.now()))

            val results = race(
                { service.setRole("A", "B", MemberRole.MEMBER) },
                { service.setRole("B", "A", MemberRole.MEMBER) },
            )

            val admins = members.findAll().count { it.role == MemberRole.ADMIN && it.status == MemberStatus.APPROVED }
            assertEquals(1, admins, "라운드 $it — 결과 $results")
            // 먼저 잠근 쪽이 이기고, 뒤에 온 쪽은 이미 관리자가 아니다
            assertEquals(listOf("NOT_ADMIN", "OK"), results.sorted(), "라운드 $it")
        }
    }

    @Test
    fun `가입을 동시에 두 번 눌러도 행은 하나고 둘 다 성공한다`() {
        repeat(ROUNDS) {
            members.deleteAll()
            val results = race({ service.join("u1", "철수") }, { service.join("u1", "철수") })

            assertEquals(listOf("OK", "OK"), results, "라운드 $it — 둘째가 500 이면 안 된다")
            assertEquals(1, members.count())
        }
    }
}
