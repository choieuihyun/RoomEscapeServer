package com.my_dream.server.club

import com.my_dream.server.domain.ClubEvent
import com.my_dream.server.domain.ClubEventParticipantRepository
import com.my_dream.server.domain.ClubEventRepository
import com.my_dream.server.domain.ClubMember
import com.my_dream.server.domain.ClubMemberRepository
import com.my_dream.server.domain.PayInfoRepository
import com.my_dream.server.domain.Settlement
import com.my_dream.server.domain.SettlementItem
import com.my_dream.server.domain.SettlementItemRepository
import com.my_dream.server.domain.SettlementRepository
import com.my_dream.server.domain.SettlementShare
import com.my_dream.server.domain.SettlementShareRepository
import com.my_dream.server.domain.SettlementTransfer
import com.my_dream.server.domain.SettlementTransferRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

// --- 계약 (Floduler `작업명세서.md` §4.13). 모양을 바꾸려면 그 문서부터 ---

/**
 * 저장 본문. **금액은 `Any?` 로 받는다** — 잭슨은 숫자 칸의 `1.5` 를 1 로, `"12"` 를 12 로 조용히 바꾼다.
 * 정수인지는 [wholeNumber] 가 본다 (D37). 빠진 값도 여기서 걸러야 계약의 `BAD_SETTLEMENT` 가 나간다
 */
data class SettlementInput(
    val version: Any? = null,
    val items: List<SettlementItemInput>? = null,
)

data class SettlementItemInput(
    val label: String? = null,
    val amount: Any? = null,
    val payerUid: String? = null,
    val shares: List<ShareInput>? = null,
)

data class ShareInput(val uid: String? = null, val amount: Any? = null)

data class TransferInput(val fromUid: String? = null, val toUid: String? = null, val paid: Boolean? = null)

data class ShareDto(val uid: String, val nickname: String, val amount: Long)

data class SettlementItemDto(val id: Long, val label: String, val amount: Long, val payer: PersonDto, val shares: List<ShareDto>)

data class TransferDto(val from: PersonDto, val to: PersonDto, val amount: Long, val paid: Boolean, val paidAt: Instant?)

data class PayInfoDto(val bankText: String?, val kakaopayUrl: String?)

data class SettlementDto(
    val eventId: Long,
    /** 정산이 없으면 0. 저장할 때마다 +1, 송금 체크로는 안 오른다 */
    val version: Long,
    val updatedAt: Instant?,
    val updatedBy: PersonDto?,
    /** 보낸 순서 */
    val items: List<SettlementItemDto>,
    val total: Long,
    val transfers: List<TransferDto>,
    /** 받는 사람들 것만. 안 넣어 둔 사람도 칸은 나간다(둘 다 null) — 화면이 "송금 정보를 넣어 두면…" 을 띄운다 */
    val payInfo: Map<String, PayInfoDto>,
)

/** 송금 계산의 입력 한 줄 — [payer] 가 [amount] 를 냈고, [shares] 가 나눠 진다 */
data class SettlementLine(val payer: String, val amount: Long, val shares: Map<String, Long>)

/** 계산된 송금 한 줄 */
data class Transfer(val from: String, val to: String, val amount: Long)

/**
 * 정산 (아키텍처 D37). 일정과 **따로 된 표**에 둔다 — 정산을 고쳐도 일정 version 이 안 오른다.
 *
 * **쓰기는 일정 행을 잠그고** 한다. 두 사람이 동시에 첫 저장(version 0)을 하면 둘 다 "머리 없음" 을 보고 넣어 하나가 500 이 된다.
 * 일정 행은 참여·빼기도 잠그므로 "지금 참여자인가" 를 보는 동안 참여가 바뀌지 않는다. 일정 행을 고치지는 않는다.
 *
 * **볼 수 있는 사람**: 그 일정 참여자 · 만든 사람 · 운영진 · **지금 저장된 항목에 나오는 사람**(참여를 뺐어도 보낼 돈이 남았을 수 있다).
 */
@Service
class SettlementService(
    private val events: ClubEventRepository,
    private val participants: ClubEventParticipantRepository,
    private val members: ClubMemberRepository,
    private val heads: SettlementRepository,
    private val items: SettlementItemRepository,
    private val shares: SettlementShareRepository,
    private val paid: SettlementTransferRepository,
    private val payInfos: PayInfoRepository,
) {

    /** **아무것도 바꾸지 않는다** — "보냈어요" 정리도 저장 때만 한다. 보기만 해도 남의 체크가 바뀌면 안 된다 */
    @Transactional(readOnly = true)
    fun get(uid: String, eventId: Long): SettlementDto {
        val viewer = members.requireApproved(uid)
        val event = events.findById(eventId).orElse(null)?.takeIf { it.deletedAt == null } ?: throw ClubError.noSuchEvent()
        val stored = load(eventId)
        requireViewer(event, viewer, stored)
        return toDto(eventId, stored)
    }

    /**
     * 항목을 **통째로** 바꾼다. 확인 순서는 D31 과 같다 — 입력(잠금 없이) → 잠금 → 일정 있음 → 볼 수 있나 → **version** → uid.
     * version 이 uid 검사보다 먼저인 이유: 낡은 화면의 저장은 "다른 사람이 고쳤어요"(최신 정산을 싣는다)를 받아야 고칠 수 있다.
     * 엉뚱한 `BAD_SETTLEMENT` 를 받으면 무엇이 틀렸는지 모른다
     */
    @Transactional
    fun save(uid: String, eventId: Long, input: SettlementInput): SettlementDto {
        val viewer = members.requireApproved(uid)
        val expected = wholeNumber(input.version)?.takeIf { it >= 0 } ?: throw ClubError.badSettlement()
        val lines = validate(input.items ?: throw ClubError.badSettlement())
        val event = lockLive(eventId)
        val stored = load(eventId)
        requireViewer(event, viewer, stored)
        if ((stored.head?.version ?: 0) != expected) throw ClubError.settlementConflict(toDto(eventId, stored))
        // 지금 참여자 ∪ 지금 정산에 나오는 사람 — 참여를 뺀 사람이 든 항목이 있어도 계속 고칠 수 있게 (§4.13 · D37)
        val allowed = participants.findByEventIds(listOf(eventId)).map { it.userId }.toSet() + stored.people
        if (lines.any { line -> line.payer !in allowed || line.shares.keys.any { it !in allowed } }) throw ClubError.badSettlement()

        // 몫 → 항목 순으로 지우고 **내보낸 뒤에** 넣는다. 테스트 H2 에는 CASCADE 가 없고,
        // 지우기를 내보내기 전에 넣으면 같은 position 이 겹친다 (D30 의 구간과 같은 함정)
        shares.deleteAll(stored.shares)
        items.deleteAll(stored.items)
        items.flush()
        lines.forEachIndexed { i, line ->
            val item = items.save(SettlementItem(eventId, i, line.label, line.amount.toInt(), line.payer))
            line.shares.forEach { (who, amount) -> shares.save(SettlementShare(item, who, amount.toInt())) }
        }

        val now = Instant.now()
        // 항목을 다 비워도 머리는 남긴다 — 지우면 version 이 0 으로 돌아가 낡은 화면이 덮어쓸 수 있다 (ABA)
        stored.head?.apply {
            version += 1
            updatedAt = now
            updatedBy = uid
        } ?: heads.save(Settlement(eventId, 1, now, uid))

        // "보냈어요" 정리 — 쌍이 사라졌거나 금액이 바뀐 체크는 푼다. 옛 금액에 대한 체크라서다
        val next = transfers(lines.map { SettlementLine(it.payer, it.amount, it.shares) }).associateBy { it.from to it.to }
        paid.deleteAll(stored.paid.filter { next[it.fromUid to it.toUid]?.amount != it.amount })
        paid.flush()
        return toDto(eventId, load(eventId))
    }

    /**
     * "보냈어요" 체크·풀기. version 은 안 오른다. 같은 값이면 그대로(멱등).
     * 볼 수 없는 사람에게는 쌍이 있는지부터 알려 주지 않는다 — 403 이 404 보다 먼저다
     */
    @Transactional
    fun mark(uid: String, eventId: Long, input: TransferInput): SettlementDto {
        val viewer = members.requireApproved(uid)
        val from = input.fromUid?.trim()?.ifEmpty { null } ?: throw ClubError.badSettlement()
        val to = input.toUid?.trim()?.ifEmpty { null } ?: throw ClubError.badSettlement()
        val wanted = input.paid ?: throw ClubError.badSettlement()
        val event = lockLive(eventId)
        val stored = load(eventId)
        requireViewer(event, viewer, stored)
        val transfer = transfers(stored.lines()).firstOrNull { it.from == from && it.to == to } ?: throw ClubError.noSuchTransfer()
        if (uid != from && uid != to && !event.manageableBy(viewer)) throw ClubError.notTransferParty()

        val row = stored.paid.firstOrNull { it.fromUid == from && it.toUid == to }
        if (wanted) {
            if (row == null) {
                paid.save(SettlementTransfer(eventId, from, to, transfer.amount, Instant.now(), uid))
            } else if (row.amount != transfer.amount) {
                // 저장이 정리하므로 금액이 다를 일은 없다. 그래도 다르면 지금 금액으로 다시 체크한 것이다
                row.amount = transfer.amount
                row.paidAt = Instant.now()
                row.paidBy = uid
            }
        } else if (row != null) {
            paid.delete(row)
        }
        paid.flush()
        return toDto(eventId, load(eventId))
    }

    // --- 판정 ---

    private fun requireViewer(event: ClubEvent, viewer: ClubMember, stored: Stored) {
        val ok = event.manageableBy(viewer) ||
            viewer.userId in stored.people ||
            participants.findByEventAndUserId(event, viewer.userId) != null
        if (!ok) throw ClubError.notSettlementViewer()
    }

    private fun lockLive(id: Long): ClubEvent =
        events.findForUpdate(id)?.takeIf { it.deletedAt == null } ?: throw ClubError.noSuchEvent()

    // --- 읽기 ---

    private class Stored(
        val head: Settlement?,
        val items: List<SettlementItem>,
        val shares: List<SettlementShare>,
        val paid: List<SettlementTransfer>,
    ) {
        val sharesOf: Map<Long, List<SettlementShare>> = shares.groupBy { requireNotNull(it.item.id) }

        /** 지금 정산에 나오는 사람 — 결제한 사람과 몫이 있는 사람 */
        val people: Set<String> = items.map { it.payerUid }.toSet() + shares.map { it.userId }

        fun lines() = items.map { item ->
            SettlementLine(item.payerUid, item.amount.toLong(), sharesOf[item.id].orEmpty().associate { it.userId to it.amount.toLong() })
        }
    }

    private fun load(eventId: Long) = Stored(
        heads.findById(eventId).orElse(null),
        items.findByEventIdOrderByPosition(eventId),
        shares.findByEventId(eventId),
        paid.findByEventId(eventId),
    )

    private fun toDto(eventId: Long, s: Stored): SettlementDto {
        val transfers = transfers(s.lines())
        val names = members.findByUserIdIn(s.people + listOfNotNull(s.head?.updatedBy)).associate { it.userId to it.nickname }
        // 내보낸 멤버도 행이 남아 닉네임이 나온다. 행이 아예 없어도 화면이 깨지지 않게 빈 문자열 (일정과 같다)
        fun person(uid: String) = PersonDto(uid, names[uid].orEmpty())
        val receivers = transfers.map { it.to }.toSet()
        val infos = payInfos.findAllById(receivers).associateBy { it.userId }
        return SettlementDto(
            eventId = eventId,
            version = s.head?.version ?: 0,
            updatedAt = s.head?.updatedAt,
            updatedBy = s.head?.updatedBy?.let(::person),
            items = s.items.map { item ->
                SettlementItemDto(
                    id = requireNotNull(item.id),
                    label = item.label,
                    amount = item.amount.toLong(),
                    payer = person(item.payerUid),
                    shares = s.sharesOf[item.id].orEmpty().map { ShareDto(it.userId, names[it.userId].orEmpty(), it.amount.toLong()) },
                )
            },
            total = s.items.sumOf { it.amount.toLong() },
            transfers = transfers.map { t ->
                val check = s.paid.firstOrNull { it.fromUid == t.from && it.toUid == t.to }?.takeIf { it.amount == t.amount }
                TransferDto(person(t.from), person(t.to), t.amount, check != null, check?.paidAt)
            },
            payInfo = receivers.associateWith { infos[it]?.let { p -> PayInfoDto(p.bankText, p.kakaopayUrl) } ?: PayInfoDto(null, null) },
        )
    }

    // --- 입력 검사 (§4.13) — 틀리면 전부 BAD_SETTLEMENT 하나다. 화면이 먼저 나누므로 어느 칸인지까지는 안 알린다 ---

    private data class CleanLine(val label: String, val amount: Long, val payer: String, val shares: Map<String, Long>)

    private fun validate(raw: List<SettlementItemInput>): List<CleanLine> {
        if (raw.size > MAX_ITEMS) throw ClubError.badSettlement()
        return raw.map { i ->
            val label = i.label?.trim().orEmpty()
            if (label.codePointCount(0, label.length) !in 1..20) throw ClubError.badSettlement()
            val amount = wholeNumber(i.amount)?.takeIf { it in 1..MAX_AMOUNT } ?: throw ClubError.badSettlement()
            val payer = i.payerUid?.trim()?.ifEmpty { null } ?: throw ClubError.badSettlement()
            val list = i.shares ?: throw ClubError.badSettlement()
            if (list.size !in 1..MAX_SHARES) throw ClubError.badSettlement()
            val clean = list.map { s ->
                val who = s.uid?.trim()?.ifEmpty { null } ?: throw ClubError.badSettlement()
                who to (wholeNumber(s.amount)?.takeIf { it >= 0 } ?: throw ClubError.badSettlement())
            }
            // 겹친 사람 · 합이 안 맞는 것. 나누는 건 화면이 하고 서버는 합만 본다 (§4.13)
            if (clean.map { it.first }.toSet().size != clean.size || clean.sumOf { it.second } != amount) throw ClubError.badSettlement()
            CleanLine(label, amount, payer, clean.toMap(LinkedHashMap()))
        }
    }

    companion object {
        const val MAX_ITEMS = 30
        const val MAX_SHARES = 30
        const val MAX_AMOUNT = 10_000_000L

        /**
         * 누가 누구에게 얼마 — **송금 계산은 여기 하나뿐이다** (D37). 앱은 받은 결과를 그리기만 한다.
         *
         * 사람마다 `잔액 = 낸 돈 − 몫`. 보낼 사람(잔액 < 0)과 받을 사람(> 0)을 각각 **금액 큰 순, 같으면 uid 순**으로 세우고,
         * 앞에서부터 둘 중 작은 금액만큼 송금을 만든 뒤 0 이 된 쪽을 넘긴다.
         * - 같은 입력이면 늘 같은 결과다 — 그래야 "보냈어요" 체크가 그 줄에 붙어 있다
         * - **닉네임으로 줄 세우지 않는다.** 닉네임은 바꿀 수 있어서(D35), 이름만 바꿔도 쌍이 바뀌고 남의 체크가 풀린다
         * - 결과는 많아야 (돈이 오가는 사람 수 − 1)건이다. 최소 횟수는 보장하지 않는다
         */
        fun transfers(lines: List<SettlementLine>): List<Transfer> {
            val balance = HashMap<String, Long>()
            for (line in lines) {
                balance.merge(line.payer, line.amount, Long::plus)
                line.shares.forEach { (who, amount) -> balance.merge(who, -amount, Long::plus) }
            }
            val order = compareByDescending<Pair<String, Long>> { it.second }.thenBy { it.first }
            val givers = balance.filterValues { it < 0 }.map { it.key to -it.value }.sortedWith(order).toMutableList()
            val takers = balance.filterValues { it > 0 }.map { it.key to it.value }.sortedWith(order).toMutableList()

            val out = mutableListOf<Transfer>()
            var g = 0
            var t = 0
            while (g < givers.size && t < takers.size) {
                val (from, owe) = givers[g]
                val (to, due) = takers[t]
                val amount = minOf(owe, due)
                out += Transfer(from, to, amount)
                givers[g] = from to owe - amount
                takers[t] = to to due - amount
                if (givers[g].second == 0L) g++
                if (takers[t].second == 0L) t++
            }
            return out
        }
    }
}
