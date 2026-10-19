package com.my_dream.server.domain

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * **진짜 Postgres 에 V1~V6 을 돌리고, 엔티티가 그 스키마와 맞는지** 본다.
 *
 * 다른 테스트는 H2 에서 엔티티로 스키마를 만든다 — 그러면 마이그레이션 SQL 은 한 줄도 안 돈다.
 * 운영은 `ddl-auto=validate` 라 **SQL 과 엔티티가 어긋나면 배포한 서버가 아예 안 뜬다.**
 * 그걸 배포 전에 잡는 자리가 여기 하나다.
 *
 * 제약(UNIQUE·CHECK)도 여기서 잰다. 엔티티가 만든 H2 스키마에는 CHECK 가 없어서
 * "DB 가 막아 준다" 고 믿는 코드가 H2 테스트에서는 그냥 통과한다.
 *
 * 이미지는 운영(`docker-compose.prod.yml`)과 같은 `postgres:17`.
 * Docker 가 없으면 **실패가 아니라 건너뛴다** — 이 맥에서는 colima 를 켜고 소켓을 알려 줘야 돈다 (README "테스트").
 */
@SpringBootTest(
    properties = [
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
    ],
)
@Testcontainers(disabledWithoutDocker = true)
class MigrationValidationTest @Autowired constructor(
    private val members: ClubMemberRepository,
    private val kakaoLinks: KakaoLinkRepository,
    private val jdbc: JdbcTemplate,
) {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val pg = PostgreSQLContainer("postgres:17")
    }

    // 컨테이너는 클래스 하나가 같이 쓴다. 테스트마다 비우지 않으면 앞 테스트의 행이 남는다
    @BeforeEach
    fun clean() {
        jdbc.update("DELETE FROM club_event_participant")
        jdbc.update("DELETE FROM club_event_segment")
        jdbc.update("DELETE FROM club_event")
        jdbc.update("DELETE FROM kakao_link")
        jdbc.update("DELETE FROM club_member")
    }

    @Test
    fun `V6 까지 돌고 엔티티와 맞는다`() {
        // 컨텍스트가 떴다는 것 자체가 validate 통과다. 표가 비어 있는지까지 본다
        assertEquals(0, members.count())
        assertEquals(0, kakaoLinks.count())
    }

    @Test
    fun `한 uid 에 멤버 행은 하나뿐이다`() {
        insertMember("dup-uid")
        assertFailsWith<DataIntegrityViolationException> { insertMember("dup-uid") }
    }

    @Test
    fun `역할과 상태는 정해진 값만 들어간다`() {
        // 오타 난 값이 들어가면 enum 으로 읽을 때 터진다. 쓰는 순간 막는 쪽이 원인을 찾기 쉽다
        assertFailsWith<DataIntegrityViolationException> { insertMember("bad-role", role = "OWNER") }
        assertFailsWith<DataIntegrityViolationException> { insertMember("bad-status", status = "BANNED") }
    }

    @Test
    fun `카카오 하나는 계정 하나에만, 계정 하나에는 카카오 하나만`() {
        jdbc.update("INSERT INTO kakao_link (kakao_id, user_id, created_at) VALUES (1001, 'a', now())")
        // 같은 카카오를 다른 계정에
        assertFailsWith<DataIntegrityViolationException> {
            jdbc.update("INSERT INTO kakao_link (kakao_id, user_id, created_at) VALUES (1001, 'b', now())")
        }
        // 같은 계정에 다른 카카오 — 서비스 검사만으로는 동시에 두 번 누르면 뚫린다
        assertFailsWith<DataIntegrityViolationException> {
            jdbc.update("INSERT INTO kakao_link (kakao_id, user_id, created_at) VALUES (1002, 'a', now())")
        }
    }

    @Test
    fun `닉네임 20자는 이모지여도 들어간다`() {
        // 서비스는 코드 포인트로 20자까지 받는다. DB 가 다르게 세면 검사를 통과한 닉네임이 500 이 된다
        insertMember("emoji", nickname = "🙂".repeat(20))
        assertFailsWith<DataIntegrityViolationException> { insertMember("emoji2", nickname = "🙂".repeat(21)) }
    }

    // --- V7 동호회 일정 ---

    private fun insertEvent(
        start: Int? = null, end: Int? = null, capacity: Int? = null, date: String = "2026-11-01", title: String = "일정",
    ): Long = jdbc.queryForObject(
        """INSERT INTO club_event (title, event_date, start_min, end_min, capacity, booked, created_by, created_at, updated_at, version)
           VALUES (?, ?::date, ?, ?, ?, false, 'u', now(), now(), 0) RETURNING id""",
        Long::class.java, title, date, start, end, capacity,
    )!!

    @Test
    fun `일정 시각은 둘 다 있거나 둘 다 없고, 0 이상 2879 이하`() {
        insertEvent()
        insertEvent(start = 600, end = 2879)
        assertFailsWith<DataIntegrityViolationException> { insertEvent(start = 600) }
        assertFailsWith<DataIntegrityViolationException> { insertEvent(start = 700, end = 600) }
        assertFailsWith<DataIntegrityViolationException> { insertEvent(start = 0, end = 2880) }
    }

    @Test
    fun `정원은 없거나 2~8`() {
        insertEvent(capacity = 2)
        insertEvent(capacity = 8)
        assertFailsWith<DataIntegrityViolationException> { insertEvent(capacity = 1) }
        assertFailsWith<DataIntegrityViolationException> { insertEvent(capacity = 9) }
    }

    @Test
    fun `한 일정에 같은 사람은 한 번만, 같은 자리 구간도 하나만`() {
        val id = insertEvent()
        val join = "INSERT INTO club_event_participant (event_id, user_id, joined_at) VALUES (?, 'u1', now())"
        jdbc.update(join, id)
        assertFailsWith<DataIntegrityViolationException> { jdbc.update(join, id) }
        val seg = "INSERT INTO club_event_segment (event_id, position, theme_name, start_min) VALUES (?, 0, '테마', 600)"
        jdbc.update(seg, id)
        assertFailsWith<DataIntegrityViolationException> { jdbc.update(seg, id) }
    }

    @Test
    fun `제목 60자는 이모지여도 들어간다`() {
        insertEvent(title = "🙂".repeat(60))
        assertFailsWith<DataIntegrityViolationException> { insertEvent(title = "🙂".repeat(61)) }
    }

    private fun insertMember(uid: String, role: String = "MEMBER", status: String = "PENDING", nickname: String = "닉") {
        jdbc.update(
            "INSERT INTO club_member (user_id, nickname, role, status, created_at) VALUES (?, ?, ?, ?, now())",
            uid, nickname, role, status,
        )
    }
}
