package com.my_dream.server.club

import com.my_dream.server.domain.ClubEventParticipantRepository
import com.my_dream.server.domain.ClubEventRepository
import com.my_dream.server.domain.ClubEventSegmentRepository
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
import kotlin.test.assertTrue

/**
 * 일정의 **동시에** — 진짜 Postgres 의 행 잠금으로 잰다 (D31). H2 는 잠금 동작이 달라 여기서는 못 믿는다.
 *
 * 하나씩 하면 맞는데 동시에 하면 틀리는 종류라, 잠금이 빠져도 운이 좋으면 지나간다. 그래서 여러 번 돌린다.
 * 정렬(`nulls first`)도 여기서 본다 — H2 와 Postgres 의 NULL 순서가 반대다.
 */
@SpringBootTest(
    properties = [
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
    ],
)
@Testcontainers(disabledWithoutDocker = true)
class ClubEventConcurrencyTest @Autowired constructor(
    private val service: ClubEventService,
    private val members: ClubMemberRepository,
    private val events: ClubEventRepository,
    private val segments: ClubEventSegmentRepository,
    private val participants: ClubEventParticipantRepository,
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
    fun setUp() {
        participants.deleteAll()
        segments.deleteAll()
        events.deleteAll()
        members.deleteAll()
        listOf("나", "너", "쟤").forEach { members.save(ClubMember(it, it, MemberRole.MEMBER, MemberStatus.APPROVED, Instant.now())) }
    }

    private fun input(capacity: Int? = null, title: String = "일정", startMin: Int? = null, endMin: Int? = null, version: Long? = null) =
        EventInput(title, "2026-11-07", startMin, endMin, null, capacity, false, null, emptyList(), version)

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
    fun `마지막 한 자리에 둘이 동시에 눌러도 한 명만 들어간다`() {
        repeat(ROUNDS) { round ->
            val id = service.create("나", input(capacity = 2)).id   // 만든 사람 + 한 자리
            val results = race({ service.join("너", id) }, { service.join("쟤", id) })

            assertEquals(listOf("EVENT_FULL", "OK"), results.sorted(), "라운드 $round")
            assertEquals(2, service.get("나", id).count, "라운드 $round — 정원 2 를 넘으면 안 된다")
        }
    }

    @Test
    fun `정원 줄이기와 참여가 엇갈려도 정원을 넘지 않는다`() {
        repeat(ROUNDS) { round ->
            val made = service.create("나", input(capacity = 3))
            service.join("너", made.id)   // 2/3
            val results = race(
                { service.update("나", made.id, input(capacity = 2, version = made.version)) },
                { service.join("쟤", made.id) },
            )

            val now = service.get("나", made.id)
            assertTrue(now.count <= (now.capacity ?: Int.MAX_VALUE), "라운드 $round — ${now.count}/${now.capacity} 결과 $results")
            // 먼저 잠근 쪽이 이긴다: 줄이기가 먼저면 참여가 EVENT_FULL, 참여가 먼저면 줄이기가 CAPACITY_TOO_SMALL
            assertTrue(results.sorted() in listOf(listOf("EVENT_FULL", "OK"), listOf("CAPACITY_TOO_SMALL", "OK")), "라운드 $round — $results")
        }
    }

    @Test
    fun `같은 version 으로 두 사람이 동시에 고치면 하나만 들어간다`() {
        repeat(ROUNDS) { round ->
            val made = service.create("나", input(title = "처음"))
            members.findByUserId("너")!!.let { it.role = MemberRole.ADMIN; members.save(it) }   // 운영진도 고칠 수 있다
            val results = race(
                { service.update("나", made.id, input(title = "나", version = made.version)) },
                { service.update("너", made.id, input(title = "너", version = made.version)) },
            )

            assertEquals(listOf("EVENT_CONFLICT", "OK"), results.sorted(), "라운드 $round")
            assertEquals(made.version + 1, service.get("나", made.id).version, "라운드 $round — 한 번만 올랐다")
        }
    }

    @Test
    fun `같은 날이면 하루 종일 일정이 먼저 온다`() {
        // Postgres 는 오름차순에서 NULL 을 맨 뒤에 둔다. 쿼리에 `nulls first` 를 안 적으면 여기서 뒤집힌다
        val timed = service.create("나", input(title = "저녁", startMin = 1140, endMin = 1260)).id
        val allDay = service.create("나", input(title = "하루 종일")).id
        val early = service.create("나", input(title = "아침", startMin = 600, endMin = 700)).id

        assertEquals(listOf(allDay, early, timed), service.list("나", "2026-11-07", "2026-11-07").map { it.id })
    }
}
