package com.my_dream.server.club

import com.my_dream.server.domain.ClubMemberRepository
import com.my_dream.server.domain.MemberStatus
import com.my_dream.server.domain.PlayedSource
import com.my_dream.server.domain.PlayedTheme
import com.my_dream.server.domain.PlayedThemeRepository
import com.my_dream.server.domain.StoreRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException

// --- 계약 (Floduler `작업명세서.md` §4.9). 모양을 바꾸려면 그 문서부터 ---

/** 직접 기록 본문. **전부 null 을 받는다** — 빠진 값을 여기서 걸러야 계약의 `BAD_PLAYED` 가 나간다 */
data class PlayedInput(
    val themeKey: String? = null,
    val themeName: String? = null,
    val branchName: String? = null,
    val playedOn: String? = null,
    /** `MANUAL`(기본) · `PLAN`(개인 "다녀온 일정" 에서). `EVENT` 는 서버 자동 기록만 쓴다 */
    val source: String? = null,
)

data class PlayedDto(
    val id: Long,
    val themeKey: String?,
    val themeName: String,
    val branchName: String?,
    val playedOn: LocalDate?,
    /** [themeKey] 로 서버가 아는 매장 이름. 키가 없거나(직접 적음) 모르는 키면 null */
    val storeName: String?,
    val source: PlayedSource,
    val eventId: Long?,
    val createdAt: Instant,
)

/** 해 본 테마 (아키텍처 D33). 모두 승인 멤버만 — 멤버끼리는 서로의 기록이 보인다 */
@Service
class PlayedService(
    private val played: PlayedThemeRepository,
    private val members: ClubMemberRepository,
    private val stores: StoreRepository,
    private val wishes: WishService,
) {

    @Transactional(readOnly = true)
    fun mine(uid: String): List<PlayedDto> {
        members.requireApproved(uid)
        return rowsOf(uid)
    }

    @Transactional(readOnly = true)
    fun ofMember(uid: String, target: String): List<PlayedDto> {
        members.requireApproved(uid)
        // 가입 신청 중이거나 내보낸 사람의 기록은 안 보인다. 없는 사람과 같은 답이다
        if (members.findByUserId(target)?.status != MemberStatus.APPROVED) throw ClubError.noSuchMember()
        return rowsOf(target)
    }

    /**
     * 같은 테마가 이미 있으면 **그 행을 그대로** 준다(멱등 · source 도 안 바꾼다). 판정은 [PlayedMatch].
     * 같은 순간 두 번 오면 겹친 행이 생길 수 있다 — 받아들인다 (D33: 막으려면 멤버 행을 잠가야 하는데 D28 의 잠금 순서와 엉킨다)
     */
    @Transactional
    fun add(uid: String, input: PlayedInput): PlayedDto {
        members.requireApproved(uid)
        val name = text(input.themeName, max = 40) ?: throw ClubError.badPlayed()
        val nameKey = PlayedMatch.nameKey(name)
        // "!!!" 나 이모지만 있는 이름은 정리하면 빈다. 받으면 그런 이름끼리 전부 같다고 나온다
        if (nameKey.isEmpty()) throw ClubError.badPlayed()
        val key = text(input.themeKey, max = 80)
        val branch = text(input.branchName, max = 40)
        val playedOn = input.playedOn?.let {
            try {
                LocalDate.parse(it)
            } catch (e: DateTimeParseException) {
                throw ClubError.badPlayed()
            }
        }
        val source = when (input.source) {
            null, "MANUAL" -> PlayedSource.MANUAL
            "PLAN" -> PlayedSource.PLAN
            else -> throw ClubError.badPlayed()   // EVENT 는 자동 기록만
        }

        val row = played.findByUserId(uid).firstOrNull { PlayedMatch.same(it.themeKey, it.nameKey, key, nameKey) }
            ?: played.save(PlayedTheme(uid, key, name, branch, nameKey, playedOn, source, null, Instant.now()))
                // 새로 생겼을 때만 — 이미 있던 기록을 다시 적은 것으로는 하고 싶은 테마를 안 지운다 (D36)
                .also { wishes.forgetPlayed(listOf(it)) }
        return withStores(listOf(row)).single()
    }

    @Transactional
    fun delete(uid: String, id: Long): DeletedDto {
        members.requireApproved(uid)
        // 남의 것과 없는 것을 같은 404 로 — 갈라 주면 남의 기록 id 가 있는지 확인하는 수단이 된다
        val row = played.findByIdAndUserId(id, uid) ?: throw ClubError.noSuchPlayed()
        played.delete(row)
        return DeletedDto(id, true)
    }

    /** 최근 순 — 다녀온 날, 없으면 적은 날 */
    private fun rowsOf(uid: String): List<PlayedDto> =
        withStores(
            played.findByUserId(uid).sortedWith(
                compareByDescending<PlayedTheme> { it.playedOn ?: it.createdAt.atZone(KST).toLocalDate() }
                    .thenByDescending { it.createdAt }
                    .thenByDescending { it.id },
            ),
        )

    private fun withStores(rows: List<PlayedTheme>): List<PlayedDto> {
        val storeOf = stores.brandsOf(rows.map { it.themeKey })
        return rows.map {
            PlayedDto(requireNotNull(it.id), it.themeKey, it.themeName, it.branchName, it.playedOn, storeOf(it.themeKey), it.source, it.eventId, it.createdAt)
        }
    }

    /** 앞뒤 공백을 지우고 **코드 포인트**로 센다 (D28). 비면 null — "빈 문자열" 과 "없음" 을 두 가지로 두지 않는다 */
    private fun text(raw: String?, max: Int): String? {
        val s = raw?.trim()?.ifEmpty { null } ?: return null
        if (s.codePointCount(0, s.length) > max) throw ClubError.badPlayed()
        return s
    }
}

/**
 * 테마 키(`play33-konkuk:18`)의 앞부분으로 매장 이름을 찾는 함수를 돌려준다. 키가 없거나 모르는 매장이면 null.
 * **한 번에** 찾는다 — 줄마다 물으면 목록 하나에 쿼리가 줄 수만큼 는다. 해 본 테마 · 하고 싶은 테마(D36)가 같이 쓴다
 */
fun StoreRepository.brandsOf(themeKeys: Collection<String?>): (String?) -> String? {
    fun storeKey(themeKey: String?) = themeKey?.substringBefore(':', missingDelimiterValue = "")?.ifEmpty { null }
    val brand = findByStoreKeyIn(themeKeys.mapNotNull(::storeKey).toSet()).associate { it.storeKey to it.brand }
    return { key -> storeKey(key)?.let(brand::get) }
}
