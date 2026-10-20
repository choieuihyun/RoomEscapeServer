package com.my_dream.server.domain

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.core.io.ClassPathResource
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

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
    private val played: PlayedThemeRepository,
    private val stores: StoreRepository,
    private val themes: ThemeRepository,
    private val slots: TimeSlotRepository,
    private val watches: WatchRepository,
    private val logs: NotificationLogRepository,
    private val jdbc: JdbcTemplate,
    private val tx: TransactionTemplate,
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
        jdbc.update("DELETE FROM notification_log")
        jdbc.update("DELETE FROM watch")
        jdbc.update("DELETE FROM member_profile")
        jdbc.update("DELETE FROM club_event_reminder")
        jdbc.update("DELETE FROM played_theme")
        jdbc.update("DELETE FROM club_event_availability")
        jdbc.update("DELETE FROM club_event_candidate")
        jdbc.update("DELETE FROM club_event_participant")
        jdbc.update("DELETE FROM club_event_segment")
        jdbc.update("DELETE FROM club_event")
        jdbc.update("DELETE FROM kakao_link")
        jdbc.update("DELETE FROM club_member")
    }

    @Test
    fun `V13 까지 돌고 엔티티와 맞는다`() {
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
    fun `일정 시각은 없거나, 시작만 있거나, 시작·끝 다 있고, 0 이상 2879 이하`() {
        insertEvent()
        insertEvent(start = 600, end = 2879)
        insertEvent(start = 600)        // V8 — 시작만
        insertEvent(start = 2879)
        assertFailsWith<DataIntegrityViolationException> { insertEvent(end = 600) }
        assertFailsWith<DataIntegrityViolationException> { insertEvent(start = 2880) }
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

    // --- V9 시간 협의 ---

    @Test
    fun `날짜 미정 일정, 후보는 일정마다 날짜 하나씩, 응답은 후보마다 한 사람 하나씩`() {
        val id = jdbc.queryForObject(
            """INSERT INTO club_event (title, event_date, booked, created_by, created_at, updated_at, version)
               VALUES ('미정', NULL, false, 'u', now(), now(), 0) RETURNING id""",
            Long::class.java,
        )!!
        val cand = "INSERT INTO club_event_candidate (event_id, cand_date) VALUES (?, '2026-11-07') RETURNING id"
        val candId = jdbc.queryForObject(cand, Long::class.java, id)!!
        assertFailsWith<DataIntegrityViolationException> { jdbc.queryForObject(cand, Long::class.java, id) }

        val answer = "INSERT INTO club_event_availability (candidate_id, user_id, available, start_min, end_min, updated_at) VALUES (?, ?, ?, ?, ?, now())"
        jdbc.update(answer, candId, "a", true, null, null)          // 종일 됨
        jdbc.update(answer, candId, "b", false, null, null)         // 안 됨
        jdbc.update(answer, candId, "c", true, 1140, 2879)          // 됨 + 시간대
        assertFailsWith<DataIntegrityViolationException> { jdbc.update(answer, candId, "a", true, null, null) }
        assertFailsWith<DataIntegrityViolationException> { jdbc.update(answer, candId, "d", false, 600, 700) }
        assertFailsWith<DataIntegrityViolationException> { jdbc.update(answer, candId, "e", true, 600, null) }
        assertFailsWith<DataIntegrityViolationException> { jdbc.update(answer, candId, "f", true, 700, 700) }
        assertFailsWith<DataIntegrityViolationException> { jdbc.update(answer, candId, "g", true, 700, 2880) }
        // CASCADE 를 일부러 안 걸었다 — 응답이 남은 후보는 못 지운다. 서비스가 응답부터 지운다 (D32)
        assertFailsWith<DataIntegrityViolationException> { jdbc.update("DELETE FROM club_event_candidate WHERE id = ?", candId) }
    }

    // --- V10 해 본 테마 ---

    @Test
    fun `해 본 테마 — 이모지 40자 이름, 정해진 source 만, 일정 표시 열`() {
        val insert = "INSERT INTO played_theme (user_id, theme_name, name_key, source, created_at) VALUES ('u', ?, 'k', ?, now())"
        jdbc.update(insert, "🙂".repeat(40), "MANUAL")
        jdbc.update(insert, "A", "PLAN")
        jdbc.update(insert, "A", "EVENT")
        assertFailsWith<DataIntegrityViolationException> { jdbc.update(insert, "🙂".repeat(41), "MANUAL") }
        assertFailsWith<DataIntegrityViolationException> { jdbc.update(insert, "A", "GUESS") }
        // 같은 테마를 두 번 넣는 것은 DB 가 막지 않는다 — 규칙이 이행적이지 않아 UNIQUE 로 못 건다 (D33)
        jdbc.update(insert, "A", "MANUAL")
        val id = insertEvent()
        assertEquals(1, jdbc.update("UPDATE club_event SET played_recorded_at = now() WHERE id = ?", id))
    }

    // --- V11 전날 알림 기록 ---

    @Test
    fun `전날 알림 — 같은 사람 같은 날짜의 SENT 는 하나, FAILED 는 여러 번`() {
        val id = insertEvent()
        val insert = "INSERT INTO club_event_reminder (event_id, user_id, remind_for, outcome, sent_at) VALUES (?, 'u', '2026-11-01', ?, now())"
        jdbc.update(insert, id, "FAILED")
        jdbc.update(insert, id, "FAILED")
        jdbc.update(insert, id, "SENT")
        assertFailsWith<DataIntegrityViolationException> { jdbc.update(insert, id, "SENT") }
        assertFailsWith<DataIntegrityViolationException> { jdbc.update(insert, id, "MAYBE") }
    }

    // --- V12 멤버 프로필 ---

    @Test
    fun `프로필 — 이모지 20자 이름, 정해진 MBTI 만, 방 수 0~9999, 한 사람 한 행`() {
        val insert = """INSERT INTO member_profile (user_id, real_name, birth_date, mbti, room_count, genres, life_theme_name, updated_at)
                        VALUES (?, ?, '1997-06-28', ?, ?, '공포,추리', '비밀의 화원', now())"""
        jdbc.update(insert, "a", "🙂".repeat(20), "INTJ", 0)
        jdbc.update(insert, "b", "철수", "UNKNOWN", 9999)
        assertFailsWith<DataIntegrityViolationException> { jdbc.update(insert, "c", "🙂".repeat(21), "INTJ", 1) }
        assertFailsWith<DataIntegrityViolationException> { jdbc.update(insert, "d", "철수", "XXXX", 1) }
        assertFailsWith<DataIntegrityViolationException> { jdbc.update(insert, "e", "철수", "INTJ", 10000) }
        assertFailsWith<DataIntegrityViolationException> { jdbc.update(insert, "f", "철수", "INTJ", -1) }
        assertFailsWith<DataIntegrityViolationException> { jdbc.update(insert, "a", "철수", "INTJ", 1) }
    }

    @Test
    fun `랭킹 쿼리가 Postgres 에서 돈다 — 승인 멤버만, 사람·날짜별 개수`() {
        // 생성자 식 + 교차 조인 + group by 는 H2 에서만 돌아 봤다. 방언 차이는 운영에서 500 으로 처음 보인다
        insertMember("u", status = "APPROVED")
        insertMember("p", status = "PENDING")
        val insert = "INSERT INTO played_theme (user_id, theme_name, name_key, played_on, source, created_at) VALUES (?, ?, ?, '2026-10-05', 'MANUAL', now())"
        jdbc.update(insert, "u", "A", "a")
        jdbc.update(insert, "u", "B", "b")
        jdbc.update(insert, "p", "A", "a")
        val rows = played.countByDay(java.time.LocalDate.of(2026, 10, 1), java.time.LocalDate.of(2026, 10, 31))
        assertEquals(listOf(PlayedDayCount("u", "닉", java.time.LocalDate.of(2026, 10, 5), 2)), rows)
    }

    @Test
    fun `승인일 열이 있다`() {
        insertMember("u", status = "APPROVED")
        assertEquals(1, jdbc.update("UPDATE club_member SET approved_at = now() WHERE user_id = 'u'"))
    }

    @Test
    fun `V13 은 내보낸 사람의 감시만 지우고 알림 기록은 CASCADE 로 같이 — 내보내기의 벌크 쿼리도 Postgres 에서 돈다`() {
        insertMember("out", status = "REMOVED")
        insertMember("in", status = "APPROVED")
        val store = stores.findAll().firstOrNull() ?: stores.save(Store("v13-test", "테스트", "테스트점"))
        val theme = themes.save(Theme(store, "v13-${System.nanoTime()}", "테마"))
        val slot = slots.save(TimeSlot(theme, LocalDate.now().plusDays(1), LocalTime.of(19, 0), available = false, lastCheckedAt = Instant.now()))
        val out = watches.save(Watch("out", slot, Instant.now()))
        val stay = watches.save(Watch("in", slot, Instant.now()))
        logs.save(NotificationLog(out, Instant.now(), "SENT"))
        logs.save(NotificationLog(stay, Instant.now(), "SENT"))

        // 이미 적용된 이행을 한 번 더 돌린다 — 지우기만 하는 SQL 이라 몇 번 돌아도 결과가 같다
        jdbc.execute(ClassPathResource("db/migration/V13__removed_member_watches.sql").getContentAsString(Charsets.UTF_8))
        assertEquals(listOf("in"), watches.findAll().map { it.userId })
        assertEquals(1, logs.count(), "내보낸 사람 감시의 알림 기록은 CASCADE 로 같이 지워졌다")

        tx.execute {
            logs.deleteByWatchUserId("in")
            watches.deleteByUserIdInBulk("in")
        }
        assertEquals(0, watches.count())
        assertEquals(0, logs.count())
    }

    private fun insertMember(uid: String, role: String = "MEMBER", status: String = "PENDING", nickname: String = "닉") {
        jdbc.update(
            "INSERT INTO club_member (user_id, nickname, role, status, created_at) VALUES (?, ?, ?, ?, now())",
            uid, nickname, role, status,
        )
    }
}
