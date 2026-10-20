package com.my_dream.server.club

import com.my_dream.server.domain.ClubMember
import com.my_dream.server.domain.ClubMemberRepository
import com.my_dream.server.domain.ClubWish
import com.my_dream.server.domain.ClubWishRepository
import com.my_dream.server.domain.MemberRole
import com.my_dream.server.domain.MemberStatus
import com.my_dream.server.domain.Store
import com.my_dream.server.domain.StoreRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 하고 싶은 테마 (§4.12 · D36) — 멱등·한도, 대표와만 비교하는 묶기, 해 보면 지우기 */
@DataJpaTest
@Import(WishService::class, PlayedService::class, PlayedRecorder::class, ClubEventService::class)
class WishServiceTest @Autowired constructor(
    private val service: WishService,
    private val playedService: PlayedService,
    private val recorder: PlayedRecorder,
    private val events: ClubEventService,
    private val members: ClubMemberRepository,
    private val wishes: ClubWishRepository,
    private val stores: StoreRepository,
) {

    @BeforeEach
    fun setUp() {
        listOf("가", "나", "다", "라").forEach { members.save(ClubMember(it, "닉$it", MemberRole.MEMBER, MemberStatus.APPROVED, Instant.now())) }
        members.save(ClubMember("대기", "대기", MemberRole.MEMBER, MemberStatus.PENDING, Instant.now()))
        stores.save(Store("play33-konkuk", "플레이33", "건대점"))
    }

    private fun wish(name: String, key: String? = null, branch: String? = null) = WishInput(key, name, branch)

    private fun assertClubError(code: String, status: HttpStatus, block: () -> Unit) {
        val e = assertFailsWith<ClubError> { block() }
        assertEquals(code, e.code)
        assertEquals(status, e.status)
    }

    /** 만든 때를 정해서 넣는다 — 묶기는 (만든 때, id) 순이라 같은 순간에 넣으면 순서가 id 에만 달린다 */
    private fun at(uid: String, minute: Long, name: String, key: String? = null) =
        wishes.save(ClubWish(uid, key, name, null, PlayedMatch.nameKey(name), Instant.parse("2026-10-20T00:00:00Z").plusSeconds(minute * 60)))

    // --- 내 목록 ---

    @Test
    fun `더하면 최근 순으로 보이고, 키로 매장 이름을 채운다`() {
        service.add("가", wish("목격자", "play33-konkuk:18", " 건대점 "))
        service.add("가", wish("직접 적은 테마"))
        val mine = service.mine("가")
        assertEquals(listOf("직접 적은 테마", "목격자"), mine.map { it.themeName })
        assertEquals("플레이33", mine[1].storeName)
        assertEquals("건대점", mine[1].branchName)
        assertNull(mine[0].storeName, "키 없음")
    }

    @Test
    fun `같은 테마는 그 행 그대로 — 키가 없어도 정리한 이름이 같으면`() {
        val first = service.add("가", wish("비밀의 화원"))
        assertEquals(first.id, service.add("가", wish("비밀의화원 ")).id)
        assertEquals(first.id, service.add("가", wish("비밀의 화원", "play33-konkuk:3")).id, "한쪽에 키가 없으면 이름으로 (D33)")
        assertEquals(1, wishes.count())
    }

    @Test
    fun `틀린 입력은 BAD_WISH`() {
        for (bad in listOf(wish(""), wish("   "), wish("!!!"), wish("가".repeat(41)), wish("테마", "k".repeat(81)), wish("테마", branch = "가".repeat(41)), WishInput())) {
            assertClubError("BAD_WISH", HttpStatus.BAD_REQUEST) { service.add("가", bad) }
        }
        assertClubError("NOT_CLUB_MEMBER", HttpStatus.FORBIDDEN) { service.add("대기", wish("테마")) }
    }

    @Test
    fun `200개가 한도 — 이미 담은 테마를 다시 누르는 것은 한도보다 먼저 본다`() {
        repeat(200) { at("가", it.toLong(), "테마$it") }
        assertClubError("TOO_MANY_WISHES", HttpStatus.CONFLICT) { service.add("가", wish("새 테마")) }
        assertEquals("테마7", service.add("가", wish("테마7")).themeName)
    }

    @Test
    fun `남의 위시는 못 지운다 — 없는 것과 같은 404`() {
        val mine = service.add("가", wish("목격자"))
        assertClubError("NO_SUCH_WISH", HttpStatus.NOT_FOUND) { service.delete("나", mine.id) }
        assertClubError("NO_SUCH_WISH", HttpStatus.NOT_FOUND) { service.delete("가", 999_999) }
        assertEquals(DeletedDto(mine.id, true), service.delete("가", mine.id))
    }

    @Test
    fun `이미 해 본 테마도 위시로 받는다`() {
        playedService.add("가", PlayedInput(themeName = "목격자"))
        service.add("가", wish("목격자"))
        assertEquals(1, service.mine("가").size)
    }

    // --- 묶음 ---

    @Test
    fun `원하는 사람이 많은 순, 같으면 가장 최근에 원한 순, 1명짜리도 싣는다`() {
        at("가", 1, "A")
        at("나", 2, "A")
        at("다", 3, "B")
        at("라", 4, "C")
        at("가", 5, "B")
        val groups = service.club("가")
        assertEquals(listOf("B", "A", "C"), groups.map { it.themeName }, "둘인 B·A 중 B 가 더 최근(5분)")
        assertEquals(listOf("다", "가"), groups[0].wanters.map { it.uid }, "since 오래된 순")
        assertEquals("닉다", groups[0].wanters[0].nickname)
    }

    @Test
    fun `대표하고만 비교한다 — 키 없는 위시가 다리가 되어 다른 두 테마를 묶지 않는다`() {
        at("가", 1, "목격자", "play33-konkuk:18")   // 대표
        at("나", 2, "목격자")                         // 키 없음 → 대표와 이름이 같다 → 같은 묶음
        at("다", 3, "목격자", "keyescape-1:5")       // 대표와 키가 다르다 → 새 묶음 (키 없는 "나" 와는 같지만 비교하지 않는다)
        val groups = service.club("가").associate { it.themeKey to it.wanters.map { w -> w.uid } }
        assertEquals(mapOf<String?, List<String>>("play33-konkuk:18" to listOf("가", "나"), "keyescape-1:5" to listOf("다")), groups)
        assertEquals("플레이33", service.club("가").first { it.themeKey == "play33-konkuk:18" }.storeName)
    }

    @Test
    fun `키 없는 대표에는 키가 다른 위시들이 다 들어온다`() {
        at("가", 1, "목격자")
        at("나", 2, "목격자", "play33-konkuk:18")
        at("다", 3, "목격자", "keyescape-1:5")
        val g = service.club("가").single()
        assertNull(g.themeKey, "묶음의 테마 칸은 대표 값")
        assertEquals(listOf("가", "나", "다"), g.wanters.map { it.uid })
    }

    @Test
    fun `한 사람은 한 번 — 겹친 행이 생겨도`() {
        at("가", 1, "목격자")
        at("가", 2, "목격자 ")   // 같은 순간 두 번 눌러 겹친 행 (받아들인다 · D36)
        val g = service.club("나").single()
        assertEquals(1, g.wanters.size)
        assertEquals(Instant.parse("2026-10-20T00:01:00Z"), g.wanters.single().since, "가장 이른 때")
    }

    @Test
    fun `승인 멤버 것만 묶고, 해 본 사람은 대표 기준 승인 멤버만`() {
        at("가", 1, "목격자", "play33-konkuk:18")
        at("대기", 2, "목격자")
        members.findByUserId("다")!!.status = MemberStatus.REMOVED
        at("다", 3, "비밀의 화원")
        playedService.add("나", PlayedInput(themeName = "목격자"))                                   // 키 없이 — 대표와 같다
        playedService.add("라", PlayedInput(themeName = "목격자", themeKey = "keyescape-1:5"))      // 대표와 키가 다르다
        val groups = service.club("가")
        assertEquals(listOf("목격자"), groups.map { it.themeName }, "내보낸 사람의 위시는 남지만 안 보인다")
        assertEquals(listOf("가"), groups.single().wanters.map { it.uid })
        assertEquals(listOf("나"), groups.single().playedBy)
        assertEquals(2, wishes.findAll().count { it.userId in setOf("대기", "다") }, "지우지는 않는다")
    }

    @Test
    fun `색인이 PlayedMatch 와 같은 답을 낸다`() {
        // 묶기·playedBy 는 비교를 색인으로 푼다. 판정이 하나라는 약속(D33)이 색인에서 깨지지 않는지 모든 조합으로 맞대 본다
        val keys = listOf(null, "k1", "k2")
        val names = listOf("x", "y", "")
        val rows = keys.flatMap { k -> names.map { n -> k to n } }
        val index = ThemeIndex()
        rows.forEachIndexed { i, (k, n) -> index.put(k, n, i) }
        for ((qk, qn) in rows) {
            val expected = rows.indices.filter { PlayedMatch.same(rows[it].first, rows[it].second, qk, qn) }.toSet()
            assertEquals(expected, index.all(qk, qn).toSet(), "$qk / '$qn'")
        }
    }

    // --- 해 보면 지운다 ---

    @Test
    fun `직접 기록으로 해 본 테마가 새로 생기면 그 사람의 같은 테마 위시가 지워진다`() {
        service.add("가", wish("목격자", "play33-konkuk:18"))
        service.add("가", wish("비밀의 화원"))
        service.add("나", wish("목격자"))
        playedService.add("가", PlayedInput(themeName = "목격자"))   // 키 없이 — 같은 테마
        assertEquals(listOf("비밀의 화원"), service.mine("가").map { it.themeName })
        assertEquals(1, service.mine("나").size, "남의 위시는 그대로")
    }

    @Test
    fun `이미 있던 기록을 다시 적는 것으로는 안 지운다 — 해 본 테마로 만든 위시가 남는다`() {
        playedService.add("가", PlayedInput(themeName = "목격자"))
        service.add("가", wish("목격자"))
        playedService.add("가", PlayedInput(themeName = "목격자"))   // 멱등 — 새로 생긴 기록이 아니다
        assertEquals(1, service.mine("가").size)
    }

    @Test
    fun `04시 30분 자동 기록으로 생긴 해 본 테마도 위시를 지운다`() {
        service.add("가", wish("목격자"))
        service.add("나", wish("목격자"))
        service.add("다", wish("목격자"))   // 참여 안 함 — 그대로
        val e = events.create("가", EventInput("모임", "2026-11-07", null, null, null, null, true, null, listOf(SegmentInput("목격자", "건대점", "play33-konkuk:18", 600)), null))
        events.join("나", e.id)
        recorder.record(LocalDate.of(2026, 11, 10))
        assertTrue(service.mine("가").isEmpty())
        assertTrue(service.mine("나").isEmpty())
        assertEquals(1, service.mine("다").size)
    }
}
