package com.my_dream.server.club

import com.my_dream.server.domain.ClubMemberRepository
import com.my_dream.server.domain.PlayedThemeRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.text.Collator
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.Locale

data class RankDto(val rank: Int, val uid: String, val nickname: String, val count: Long)

data class RankMonthDto(val month: String, val ranks: List<RankDto>)

/**
 * 달별 랭킹 (§4.11 · 아키텍처 D35) — 그 달에 해 본 테마를 많이 기록한 사람 5등까지.
 *
 * - 센다: **지금 승인된** 멤버의 해 본 테마 중 날짜가 그 달인 것. 출처(직접·개인 일정·동호회 일정) 상관없이.
 *   같은 사람의 같은 테마는 서버가 한 행으로만 두므로 "행 수" 가 곧 "서로 다른 테마 수" 다 (D33)
 * - **오늘(KST) 뒤 날짜는 안 센다** — 해 본 테마 날짜는 범위 검사가 없어서, 막지 않으면 다음 달 1등을 미리 적을 수 있다
 * - 내보낸 멤버는 지난 달에서도 빠진다 (계약이 받아들였다)
 */
@Service
class RankingService(
    private val members: ClubMemberRepository,
    private val played: PlayedThemeRepository,
) {

    @Transactional(readOnly = true)
    fun rankings(uid: String, from: String?, to: String?, today: LocalDate = todayKst()): List<RankMonthDto> {
        members.requireApproved(uid)
        val first = month(from)
        val last = month(to)
        val span = ChronoUnit.MONTHS.between(first, last) + 1
        if (span !in 1..MAX_MONTHS) throw ClubError.badRankRange()

        val until = minOf(last.atEndOfMonth(), today)
        val rows = if (until < first.atDay(1)) emptyList() else played.countByDay(first.atDay(1), until)
        val byMonth = rows.groupBy { YearMonth.from(it.playedOn) }

        val korean = Collator.getInstance(Locale.KOREAN)
        return generateSequence(last) { it.minusMonths(1) }.take(span.toInt()).map { m ->
            val totals = byMonth[m].orEmpty()
                .groupBy { it.userId }
                .map { (userId, days) -> Triple(userId, days.first().nickname, days.sumOf { it.count }) }
                .sortedWith(compareByDescending<Triple<String, String, Long>> { it.third }.thenComparator { a, b -> korean.compare(a.second, b.second) })
            // 같은 수는 같은 순위(1, 1, 3 …) — 순위 = 나보다 많이 한 사람 수 + 1. 5등 안이면 동점자를 다 싣는다
            val ranks = totals.map { (userId, nickname, count) -> RankDto(totals.count { it.third > count } + 1, userId, nickname, count) }
            RankMonthDto(m.toString(), ranks.filter { it.rank <= TOP })
        }.toList()
    }

    /** "YYYY-MM" 만 받는다. 빠졌거나 틀리면 랭킹용 BAD_RANGE */
    private fun month(raw: String?): YearMonth = try {
        if (raw == null || !MONTH.matches(raw)) throw ClubError.badRankRange()
        YearMonth.parse(raw)
    } catch (e: DateTimeParseException) {
        throw ClubError.badRankRange()
    }

    companion object {
        const val MAX_MONTHS = 12L
        const val TOP = 5
        private val MONTH = Regex("""\d{4}-\d{2}""")
    }
}
