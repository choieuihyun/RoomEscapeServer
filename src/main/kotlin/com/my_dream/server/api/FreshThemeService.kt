package com.my_dream.server.api

import com.my_dream.server.club.ClubError
import com.my_dream.server.club.PlayedMatch
import com.my_dream.server.club.requireApproved
import com.my_dream.server.domain.ClubEventParticipantRepository
import com.my_dream.server.domain.ClubEventRepository
import com.my_dream.server.domain.ClubMemberRepository
import com.my_dream.server.domain.PlayedThemeRepository
import com.my_dream.server.domain.TimeSlotRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.text.Collator
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.Locale

// --- 계약 (Floduler `작업명세서.md` §4.9 협의 도우미) ---

data class BranchRefDto(val store: String, val branch: String)

/** `/api/schedule` 의 테마([ThemeDto])에 지점과 "참여자 중 해 본 사람" 을 더한 것 */
data class FreshThemeDto(
    val id: String,
    val name: String,
    val place: String,
    val dur: Int?,
    val genre: String?,
    val capacity: String?,
    val minPeople: Int?,
    val maxPeople: Int?,
    val horrorLevel: Double?,
    val difficulty: Double?,
    val posterUrl: String?,
    val sessions: List<SessionDto>,
    val themeKey: String,
    val storeName: String,
    val branchName: String,
    val playedBy: List<String>,
)

data class FreshDto(
    val date: LocalDate,
    /** 그날 시간표가 있는 지점이 하나라도 있나. false 면 themes 가 비어도 "열린 테마 없음" 이 아니다 */
    val collected: Boolean,
    /** 그날 시간표를 마지막으로 모은 때 */
    val checkedAt: Instant?,
    /** 지원 지점 중 그날 회차가 하나도 없는 곳 — 아직 안 열렸는지 쉬는 날인지는 서버도 모른다 */
    val missing: List<BranchRefDto>,
    /** 참여자 중 해 본 테마 기록이 하나도 없는 사람 — 그 사람의 "안 해 봄" 은 믿을 수 없다 */
    val noRecord: List<String>,
    val themes: List<FreshThemeDto>,
)

/**
 * 협의 도우미 — 그날 열린 테마 중 참여자가 안 해 본 것 (아키텍처 D33).
 *
 * **서버가 이미 모아 둔 시간표만** 읽는다. 매장 사이트로 가는 요청은 하나도 늘지 않는다.
 * 지점마다 예약 창이 달라(D21) 같은 날짜라도 모은 지점과 못 모은 지점이 섞인다 — 못 모은 지점은 [FreshDto.missing] 으로 따로 알린다.
 * 안 그러면 그 매장 테마가 "아무도 안 해 봄" 목록에서 **조용히 빠진다.**
 */
@Service
@Transactional(readOnly = true)
class FreshThemeService(
    private val members: ClubMemberRepository,
    private val events: ClubEventRepository,
    private val participants: ClubEventParticipantRepository,
    private val played: PlayedThemeRepository,
    private val slots: TimeSlotRepository,
    private val schedule: ScheduleQueryService,
) {

    fun fresh(uid: String, eventId: Long, rawDate: String?): FreshDto {
        members.requireApproved(uid)
        val event = events.findById(eventId).orElse(null)?.takeIf { it.deletedAt == null } ?: throw ClubError.noSuchEvent()
        val date = when (rawDate) {
            null -> event.date ?: throw ClubError.badRange()   // 날짜 미정이면 어느 날을 볼지 모른다
            else -> try {
                LocalDate.parse(rawDate)
            } catch (e: DateTimeParseException) {
                throw ClubError.badRange()
            }
        }

        val people = participants.findByEventIds(listOf(eventId)).map { it.userId }
        val playedByUser = played.findByUserIdIn(people.toSet()).groupBy { it.userId }

        val supported = schedule.supportedBranches()
        val supportedKeys = supported.map { it.key }.toSet()
        val rows = slots.findAllOnDate(date).filter { it.theme.store.storeKey in supportedKeys }
        val seen = rows.map { it.theme.store.storeKey }.toSet()

        val korean = Collator.getInstance(Locale.KOREAN)
        val themes = rows.groupBy { it.theme }.map { (theme, themeSlots) ->
            val store = theme.store
            val key = "${store.storeKey}:${theme.externalId}"
            val nameKey = PlayedMatch.nameKey(theme.name)
            val who = people.filter { u -> playedByUser[u].orEmpty().any { PlayedMatch.same(it.themeKey, it.nameKey, key, nameKey) } }
            val t = theme.toThemeDto(store, themeSlots)
            FreshThemeDto(
                t.id, t.name, t.place, t.dur, t.genre, t.capacity, t.minPeople, t.maxPeople, t.horrorLevel, t.difficulty, t.posterUrl, t.sessions,
                themeKey = key, storeName = store.brand, branchName = store.branchName, playedBy = who,
            )
        }.sortedWith(
            compareBy<FreshThemeDto> { it.playedBy.size }
                .thenBy(korean) { it.storeName }
                .thenBy(korean) { it.branchName }
                .thenBy(korean) { it.name },
        )

        return FreshDto(
            date = date,
            collected = rows.isNotEmpty(),
            checkedAt = rows.maxOfOrNull { it.lastCheckedAt },
            missing = supported.filter { it.key !in seen }.map { BranchRefDto(it.brand, it.branchName) },
            noRecord = people.filter { playedByUser[it].isNullOrEmpty() },
            themes = themes,
        )
    }
}
