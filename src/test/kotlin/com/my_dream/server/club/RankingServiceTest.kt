package com.my_dream.server.club

import com.my_dream.server.domain.ClubMember
import com.my_dream.server.domain.ClubMemberRepository
import com.my_dream.server.domain.MemberRole
import com.my_dream.server.domain.MemberStatus
import com.my_dream.server.domain.PlayedSource
import com.my_dream.server.domain.PlayedTheme
import com.my_dream.server.domain.PlayedThemeRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** 달별 랭킹 (§4.11 · D35) */
@DataJpaTest
@Import(RankingService::class)
class RankingServiceTest @Autowired constructor(
    private val service: RankingService,
    private val members: ClubMemberRepository,
    private val played: PlayedThemeRepository,
) {

    private val today = LocalDate.of(2026, 10, 20)

    private fun member(uid: String, nickname: String = uid, status: MemberStatus = MemberStatus.APPROVED) =
        members.save(ClubMember(uid, nickname, MemberRole.MEMBER, status, Instant.now()))

    /** [uid] 가 [on] 에 테마 [n] 개를 기록했다. 출처를 돌려 가며 넣는다 — 랭킹은 출처를 안 가린다 */
    private fun played(uid: String, on: LocalDate?, n: Int = 1) = repeat(n) { i ->
        val source = PlayedSource.entries[i % PlayedSource.entries.size]
        played.save(PlayedTheme(uid, null, "테마$i-$on", null, "테마$i$on", on, source, null, Instant.now()))
    }

    private fun ranks(from: String = "2026-10", to: String = "2026-10") = service.rankings("나", from, to, today)

    private fun assertBadRange(from: String?, to: String?) {
        val e = assertFailsWith<ClubError> { service.rankings("나", from, to, today) }
        assertEquals("BAD_RANGE", e.code)
        assertEquals("랭킹은 한 번에 12달까지 볼 수 있어요.", e.message, "일정 목록의 62일 문구가 아니다")
    }

    @Test
    fun `많이 한 순, 같은 수는 같은 순위 — 1, 1, 3`() {
        member("나", "나무")
        member("가", "가람")
        member("다", "다솜")
        played("나", LocalDate.of(2026, 10, 3), 3)
        played("가", LocalDate.of(2026, 10, 9), 2)
        played("가", LocalDate.of(2026, 10, 11), 1)   // 날짜가 달라도 그 달이면 더한다
        played("다", LocalDate.of(2026, 10, 1), 1)

        val month = ranks().single()
        assertEquals("2026-10", month.month)
        assertEquals(
            listOf(RankDto(1, "가", "가람", 3), RankDto(1, "나", "나무", 3), RankDto(3, "다", "다솜", 1)),
            month.ranks,
            "같은 순위 안에서는 닉네임 가나다순",
        )
    }

    @Test
    fun `5등에 동점자가 걸리면 다 싣는다 — 6명 이상일 수 있다`() {
        member("나")
        listOf("a", "b", "c", "d", "e", "f", "g").forEachIndexed { i, uid ->
            member(uid)
            played(uid, LocalDate.of(2026, 10, 5), if (i < 4) 10 - i else 2)   // 10 9 8 7 / 2 2 2
        }
        val r = ranks().single().ranks
        assertEquals(listOf(1, 2, 3, 4, 5, 5, 5), r.map { it.rank })

        played("h", LocalDate.of(2026, 10, 5), 1)   // 멤버가 아니다 — 안 센다
        member("i")
        played("i", LocalDate.of(2026, 10, 5), 1)   // 8등이다 — 빠진다
        assertEquals(7, ranks().single().ranks.size)
    }

    @Test
    fun `최신 달 먼저, 기록 없는 달도 빈 목록으로`() {
        member("나")
        played("나", LocalDate.of(2026, 8, 31))
        val r = ranks(from = "2026-07", to = "2026-10")
        assertEquals(listOf("2026-10", "2026-09", "2026-08", "2026-07"), r.map { it.month })
        assertEquals(listOf(0, 0, 1, 0), r.map { it.ranks.size })
    }

    @Test
    fun `오늘 뒤 날짜 · 날짜 없는 기록 · 승인 멤버가 아닌 사람은 안 센다`() {
        member("나")
        member("대기", status = MemberStatus.PENDING)
        member("내보냄", status = MemberStatus.REMOVED)
        played("나", today)
        played("나", today.plusDays(1), 5)          // 미래 — 다음 달 1등을 미리 적을 수 없다
        played("나", LocalDate.of(2026, 11, 3), 5)
        played("나", null, 5)
        played("대기", today, 9)
        played("내보냄", LocalDate.of(2026, 9, 1), 9)   // 지난 달에서도 빠진다

        val r = ranks(from = "2026-09", to = "2026-11")
        assertEquals(listOf(emptyList(), listOf(RankDto(1, "나", "나", 1)), emptyList()), r.map { it.ranks })
    }

    @Test
    fun `범위 — YYYY-MM 만, from 이 to 보다 늦으면, 12달 넘으면 BAD_RANGE`() {
        member("나")
        assertBadRange(null, "2026-10")
        assertBadRange("2026-10", null)
        assertBadRange("2026-1", "2026-10")
        assertBadRange("2026-13", "2026-13")
        assertBadRange("2026-10-01", "2026-10")
        assertBadRange("2026-11", "2026-10")
        assertBadRange("2025-10", "2026-10")   // 13달
        assertEquals(12, ranks(from = "2025-11", to = "2026-10").size)
    }

    @Test
    fun `승인 멤버만 본다`() {
        member("대기", status = MemberStatus.PENDING)
        for (uid in listOf("대기", "없음")) {
            val e = assertFailsWith<ClubError> { service.rankings(uid, "2026-10", "2026-10", today) }
            assertEquals("NOT_CLUB_MEMBER", e.code)
            assertEquals(HttpStatus.FORBIDDEN, e.status)
        }
    }
}
