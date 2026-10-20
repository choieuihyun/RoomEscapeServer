package com.my_dream.server.club

import com.my_dream.server.domain.ClubMemberRepository
import com.my_dream.server.domain.ClubWish
import com.my_dream.server.domain.ClubWishRepository
import com.my_dream.server.domain.MemberStatus
import com.my_dream.server.domain.PlayedTheme
import com.my_dream.server.domain.PlayedThemeRepository
import com.my_dream.server.domain.StoreRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

// --- 계약 (Floduler `작업명세서.md` §4.12). 모양을 바꾸려면 그 문서부터 ---

/** 더하기 본문. **전부 null 을 받는다** — 빠진 값을 여기서 걸러야 계약의 `BAD_WISH` 가 나간다 */
data class WishInput(
    val themeKey: String? = null,
    val themeName: String? = null,
    val branchName: String? = null,
)

data class WishDto(
    val id: Long,
    val themeKey: String?,
    val themeName: String,
    val branchName: String?,
    /** [themeKey] 로 서버가 아는 매장 이름. 키가 없거나 모르는 키면 null */
    val storeName: String?,
    val createdAt: Instant,
)

data class WanterDto(val uid: String, val nickname: String, val since: Instant)

/** 같은 테마를 원하는 사람 묶음. 테마 칸은 **대표**(가장 먼저 생긴 위시)의 값이다 (D36) */
data class WishGroupDto(
    val themeKey: String?,
    val themeName: String,
    val branchName: String?,
    val storeName: String?,
    /** since 오래된 순, 한 사람 한 번 */
    val wanters: List<WanterDto>,
    /** 승인 멤버 중 대표와 같은 테마를 해 본 사람 (uid 순) */
    val playedBy: List<String>,
)

/**
 * 하고 싶은 테마 (아키텍처 D36). 모두 승인 멤버만. "같은 테마" 는 [PlayedMatch] — 해 본 테마와 판정이 같아야
 * "해 봤는데 하고 싶은 목록에 남는" 일이 안 생긴다.
 */
@Service
class WishService(
    private val wishes: ClubWishRepository,
    private val members: ClubMemberRepository,
    private val played: PlayedThemeRepository,
    private val stores: StoreRepository,
) {

    /** 최근 순 */
    @Transactional(readOnly = true)
    fun mine(uid: String): List<WishDto> {
        members.requireApproved(uid)
        return withStores(wishes.findByUserId(uid).sortedWith(compareByDescending<ClubWish> { it.createdAt }.thenByDescending { it.id }))
    }

    /**
     * 같은 테마가 이미 있으면 **그 행을 그대로** 준다(멱등). 한도는 **그 뒤에** 본다 — 이미 담은 것을 다시 눌렀는데
     * "200개까지" 가 나오면 무엇을 지워야 할지 모른다(감시 D3 에서 배운 순서).
     * 이미 해 본 테마도 받는다(사용자 결정 — 다시 하고 싶을 수 있다).
     * 같은 순간 두 번 오면 겹친 행이 생길 수 있다 — 받아들인다 (D33 과 같다 · 묶음 목록은 uid 로 한 번 더 거른다)
     */
    @Transactional
    fun add(uid: String, input: WishInput): WishDto {
        members.requireApproved(uid)
        val name = text(input.themeName, max = 40) ?: throw ClubError.badWish()
        val nameKey = PlayedMatch.nameKey(name)
        // "!!!" 나 이모지만 있는 이름은 정리하면 빈다. 받으면 그런 이름끼리 전부 같다고 나온다
        if (nameKey.isEmpty()) throw ClubError.badWish()
        val key = text(input.themeKey, max = 80)
        val branch = text(input.branchName, max = 40)

        val mine = wishes.findByUserId(uid)
        val row = mine.firstOrNull { PlayedMatch.same(it.themeKey, it.nameKey, key, nameKey) }
            ?: run {
                if (mine.size >= MAX_PER_USER) throw ClubError.tooManyWishes()
                wishes.save(ClubWish(uid, key, name, branch, nameKey, Instant.now()))
            }
        return withStores(listOf(row)).single()
    }

    @Transactional
    fun delete(uid: String, id: Long): DeletedDto {
        members.requireApproved(uid)
        // 남의 것과 없는 것을 같은 404 로 — 갈라 주면 남의 위시 id 가 있는지 확인하는 수단이 된다
        val row = wishes.findByIdAndUserId(id, uid) ?: throw ClubError.noSuchWish()
        wishes.delete(row)
        return DeletedDto(id, true)
    }

    /**
     * 승인 멤버의 위시를 같은 테마끼리 묶는다.
     *
     * **대표 하나하고만 비교한다** (D36). 판정이 이행적이지 않아서, 아무 위시하고나 비교하면 키 없는 위시 하나가
     * 다리가 되어 서로 다른 두 테마가 한 묶음이 된다. 위시를 (만든 때, id) 순으로 넣으며, 같은 대표가 여럿이면 먼저 생긴 묶음에 들어간다.
     *
     * 비교는 색인으로 한다 — 30명 × 200개면 위시 6,000개(추정)라, 묶음마다 다 훑으면 수천만 번 비교한다
     */
    @Transactional(readOnly = true)
    fun club(uid: String): List<WishGroupDto> {
        members.requireApproved(uid)
        val approved = members.findByStatusOrderByCreatedAt(MemberStatus.APPROVED).associate { it.userId to it.nickname }
        val rows = wishes.findByUserIdIn(approved.keys).sortedWith(compareBy<ClubWish> { it.createdAt }.thenBy { it.id })

        val groups = mutableListOf<Group>()
        val reps = ThemeIndex()
        for (w in rows) {
            val at = reps.first(w.themeKey, w.nameKey)
            val group = if (at != null) groups[at] else Group(w).also { groups += it; reps.put(it.rep.themeKey, it.rep.nameKey, groups.lastIndex) }
            group.wanters.putIfAbsent(w.userId, w.createdAt)   // 먼저 들어온 것이 가장 이른 since 다
        }

        val playedIndex = ThemeIndex()
        val playedRows = played.findByUserIdIn(approved.keys)
        playedRows.forEachIndexed { i, p -> playedIndex.put(p.themeKey, p.nameKey, i) }
        val storeOf = stores.brandsOf(groups.map { it.rep.themeKey })

        return groups
            .sortedWith(
                compareByDescending<Group> { it.wanters.size }
                    .thenByDescending { it.wanters.values.max() }
                    .thenBy { it.rep.id },
            )
            .map { g ->
                WishGroupDto(
                    themeKey = g.rep.themeKey,
                    themeName = g.rep.themeName,
                    branchName = g.rep.branchName,
                    storeName = storeOf(g.rep.themeKey),
                    wanters = g.wanters.map { (u, since) -> WanterDto(u, approved[u].orEmpty(), since) },
                    playedBy = playedIndex.all(g.rep.themeKey, g.rep.nameKey).map { playedRows[it].userId }.toSortedSet().toList(),
                )
            }
    }

    /**
     * 해 본 테마가 **새로 생겼을 때** 그 사람의 같은 테마 위시를 지운다 (D36). 직접 기록과 04:30 자동 기록이 부른다.
     * 이미 있던 기록과 같은 테마를 다시 적은 것(멱등)은 새로 생긴 게 아니라 부르지 않는다 —
     * 그래서 이미 해 본 테마로 만든 위시는 남는다(사용자가 받아들였다)
     */
    @Transactional
    fun forgetPlayed(newRows: List<PlayedTheme>) {
        if (newRows.isEmpty()) return
        val byUser = newRows.groupBy { it.userId }
        val gone = wishes.findByUserIdIn(byUser.keys).filter { w ->
            byUser[w.userId].orEmpty().any { PlayedMatch.same(it.themeKey, it.nameKey, w.themeKey, w.nameKey) }
        }
        if (gone.isNotEmpty()) wishes.deleteAll(gone)
    }

    private class Group(val rep: ClubWish) {
        val wanters = LinkedHashMap<String, Instant>()
    }

    private fun withStores(rows: List<ClubWish>): List<WishDto> {
        val storeOf = stores.brandsOf(rows.map { it.themeKey })
        return rows.map { WishDto(requireNotNull(it.id), it.themeKey, it.themeName, it.branchName, storeOf(it.themeKey), it.createdAt) }
    }

    /** 앞뒤 공백을 지우고 **코드 포인트**로 센다 (D28). 비면 null */
    private fun text(raw: String?, max: Int): String? {
        val s = raw?.trim()?.ifEmpty { null } ?: return null
        if (s.codePointCount(0, s.length) > max) throw ClubError.badWish()
        return s
    }

    companion object {
        const val MAX_PER_USER = 200
    }
}

/**
 * [PlayedMatch.same] 을 **색인으로** 푸는 것. 줄 번호(넣은 순서)를 담고, 찾으면 그 줄 번호들을 돌려준다.
 *
 * `same(a, b)` 는 "둘 다 키가 있으면 키가 같다, 아니면 정리한 이름이 같고 비어 있지 않다" 다. 그래서 키가 있는 쪽으로 찾을 때는
 * **키가 같은 줄 + 키 없는 줄 중 이름이 같은 것**, 키가 없는 쪽으로 찾을 때는 **이름이 같은 줄 전부**다.
 * 이 규칙을 바꾸려면 [PlayedMatch.same] 과 같이 바꾼다 — `WishServiceTest` 가 둘을 맞대 본다
 */
internal class ThemeIndex {
    private val byKey = HashMap<String, MutableList<Int>>()
    private val keylessByName = HashMap<String, MutableList<Int>>()
    private val allByName = HashMap<String, MutableList<Int>>()

    fun put(key: String?, nameKey: String, at: Int) {
        if (key != null) byKey.getOrPut(key) { mutableListOf() } += at
        if (nameKey.isEmpty()) return
        if (key == null) keylessByName.getOrPut(nameKey) { mutableListOf() } += at
        allByName.getOrPut(nameKey) { mutableListOf() } += at
    }

    /** [key]·[nameKey] 와 같은 테마인 줄 전부 (넣은 순서 아님) */
    fun all(key: String?, nameKey: String): List<Int> {
        val named = if (nameKey.isEmpty()) emptyList() else if (key != null) keylessByName[nameKey].orEmpty() else allByName[nameKey].orEmpty()
        val keyed = if (key != null) byKey[key].orEmpty() else emptyList()
        return keyed + named
    }

    /** 같은 테마인 줄 중 가장 먼저 넣은 것 */
    fun first(key: String?, nameKey: String): Int? = all(key, nameKey).minOrNull()
}
