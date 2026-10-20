package com.my_dream.server.club

import com.my_dream.server.domain.ClubMember
import com.my_dream.server.domain.ClubMemberRepository
import com.my_dream.server.domain.MemberRole
import com.my_dream.server.domain.MemberStatus
import com.my_dream.server.domain.SettlementRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import java.math.BigInteger
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 정산 (§4.13 · D37) — 누가 볼 수 있나, 무엇을 받나, version, 송금 계산, "보냈어요".
 * 첫 저장이 동시에 오는 것은 진짜 Postgres 로 `ClubEventConcurrencyTest` 가 본다.
 */
@DataJpaTest
@Import(SettlementService::class, ClubEventService::class, PayInfoService::class)
class SettlementServiceTest @Autowired constructor(
    private val service: SettlementService,
    private val events: ClubEventService,
    private val payInfo: PayInfoService,
    private val members: ClubMemberRepository,
    private val heads: SettlementRepository,
) {

    private var eventId = 0L

    @BeforeEach
    fun setUp() {
        listOf("가", "나", "다", "남").forEach { member(it) }
        member("운영", role = MemberRole.ADMIN)
        member("대기", status = MemberStatus.PENDING)
        // "가" 가 만들고 "나"·"다" 가 참여했다. "남" 은 승인 멤버지만 이 일정과 상관없다
        eventId = events.create("가", EventInput("보드게임처럼", "2026-11-07", null, null, null, null, false, null, emptyList(), null)).id
        events.join("나", eventId)
        events.join("다", eventId)
    }

    private fun member(uid: String, role: MemberRole = MemberRole.MEMBER, status: MemberStatus = MemberStatus.APPROVED) =
        members.save(ClubMember(uid, "닉$uid", role, status, Instant.now()))

    private fun item(label: String, amount: Any?, payer: String, vararg shares: Pair<String, Any?>) =
        SettlementItemInput(label, amount, payer, shares.map { ShareInput(it.first, it.second) })

    /** 방탈출 60,000(가 결제, 셋이 2만씩) · 카페 9,000(나 결제, 가·나 4,500씩) */
    private fun usual() = listOf(
        item("방탈출", 60000, "가", "가" to 20000, "나" to 20000, "다" to 20000),
        item("카페", 9000, "나", "가" to 4500, "나" to 4500),
    )

    private fun save(version: Long, items: List<SettlementItemInput> = usual(), uid: String = "가") =
        service.save(uid, eventId, SettlementInput(version, items))

    private fun assertClubError(code: String, status: HttpStatus, block: () -> Unit): ClubError {
        val e = assertFailsWith<ClubError> { block() }
        assertEquals(code, e.code)
        assertEquals(status, e.status)
        return e
    }

    // --- 송금 계산 (서버 한 곳) ---

    @Test
    fun `송금 계산 — 잔액 큰 순으로 짝짓고, 합이 맞고, 많아야 사람 수 - 1 건`() {
        val t = SettlementService.transfers(
            listOf(SettlementLine("가", 60000, mapOf("가" to 20000L, "나" to 20000L, "다" to 20000L)), SettlementLine("나", 9000, mapOf("가" to 4500L, "나" to 4500L))),
        )
        // 가 +35,500 · 나 −15,500 · 다 −20,000 → 다가 먼저(더 큰 빚)
        assertEquals(listOf(Transfer("다", "가", 20000), Transfer("나", "가", 15500)), t)
    }

    @Test
    fun `금액이 같으면 uid 순 — 닉네임은 안 본다`() {
        val lines = listOf(SettlementLine("갑", 3000, mapOf("을" to 1000L, "병" to 1000L, "갑" to 1000L)))
        assertEquals(listOf(Transfer("병", "갑", 1000), Transfer("을", "갑", 1000)).sortedBy { it.from }, SettlementService.transfers(lines))
        // 받을 사람끼리도 같다
        val two = listOf(SettlementLine("z", 1000, mapOf("a" to 1000L)), SettlementLine("y", 1000, mapOf("a" to 1000L)))
        assertEquals(listOf(Transfer("a", "y", 1000), Transfer("a", "z", 1000)), SettlementService.transfers(two))
    }

    @Test
    fun `여럿이 얽혀도 송금은 많아야 사람 수 - 1 건이고 모두 0 이 된다`() {
        val lines = (1..6).map { i -> SettlementLine("p$i", i * 1000L, (1..6).associate { "p$it" to i * 1000L / 6 }.let { m -> m + ("p1" to m.getValue("p1") + i * 1000L - m.values.sum()) }) }
        val t = SettlementService.transfers(lines)
        assertTrue(t.size <= 5)
        val net = HashMap<String, Long>()
        lines.forEach { l -> net.merge(l.payer, l.amount, Long::plus); l.shares.forEach { (u, a) -> net.merge(u, -a, Long::plus) } }
        t.forEach { net.merge(it.from, it.amount, Long::plus); net.merge(it.to, -it.amount, Long::plus) }
        assertTrue(net.values.all { it == 0L }, "송금을 다 하면 모두 0 이다: $net")
    }

    // --- 보기 ---

    @Test
    fun `정산이 없으면 빈 것, version 0`() {
        val s = service.get("나", eventId)
        assertEquals(0, s.version)
        assertTrue(s.items.isEmpty())
        assertEquals(0, s.total)
        assertTrue(s.transfers.isEmpty())
        assertNull(s.updatedBy)
        assertNull(events.get("나", eventId).settlementTotal, "정산이 없으면 일정 목록 칸도 null")
    }

    @Test
    fun `저장하면 version 1, 보낸 순서, 합계, 송금, 받는 사람 송금 정보 칸`() {
        payInfo.save("가", PayInfoInput("카카오뱅크 3333-01-1234567 가", "https://qr.kakaopay.com/abc"))
        val s = save(0)
        assertEquals(1, s.version)
        assertEquals(PersonDto("가", "닉가"), s.updatedBy)
        assertEquals(listOf("방탈출", "카페"), s.items.map { it.label })
        assertEquals(listOf("가", "나", "다"), s.items[0].shares.map { it.uid })
        assertEquals("닉나", s.items[1].payer.nickname)
        assertEquals(69000, s.total)
        assertEquals(listOf("다" to "가", "나" to "가"), s.transfers.map { it.from.uid to it.to.uid })
        assertTrue(s.transfers.none { it.paid })
        assertEquals(mapOf("가" to PayInfoDto("카카오뱅크 3333-01-1234567 가", "https://qr.kakaopay.com/abc")), s.payInfo, "받는 사람 것만")
        assertEquals(69000L, events.get("남", eventId).settlementTotal, "합계는 일정을 보는 승인 멤버 모두에게")
    }

    @Test
    fun `송금 정보를 안 넣은 받는 사람도 칸은 나간다`() {
        assertEquals(mapOf("가" to PayInfoDto(null, null)), save(0).payInfo)
    }

    @Test
    fun `참여자 · 만든 사람 · 운영진은 보고, 상관없는 승인 멤버는 NOT_PARTICIPANT`() {
        save(0)
        service.get("다", eventId)
        service.get("운영", eventId)
        events.leave("가", eventId)
        service.get("가", eventId)   // 만든 사람은 참여를 취소해도 본다
        val e = assertClubError("NOT_PARTICIPANT", HttpStatus.FORBIDDEN) { service.get("남", eventId) }
        assertEquals("참여한 사람, 만든 사람, 운영진만 정산을 볼 수 있어요.", e.message)
        assertClubError("NOT_PARTICIPANT", HttpStatus.FORBIDDEN) { save(1, uid = "남") }
        assertClubError("NOT_CLUB_MEMBER", HttpStatus.FORBIDDEN) { service.get("대기", eventId) }
    }

    @Test
    fun `참여를 뺀 사람도 저장된 항목에 나오면 보고, 그 사람이 든 정산을 계속 고칠 수 있다`() {
        save(0)
        events.leave("다", eventId)
        assertEquals(1, service.get("다", eventId).version, "보낼 돈이 남은 사람은 봐야 한다")
        // "다" 는 이제 참여자가 아니지만 저장된 정산에 있다 — 통째로 다시 보내도 거절되면 안 된다
        assertEquals(2, save(1).version)
        // 저장된 정산에서 빠지면 그다음부터는 지금 참여자만 된다
        assertEquals(3, save(2, listOf(item("방탈출", 40000, "가", "가" to 20000, "나" to 20000))).version)
        assertClubError("BAD_SETTLEMENT", HttpStatus.BAD_REQUEST) { save(3) }
        assertClubError("NOT_PARTICIPANT", HttpStatus.FORBIDDEN) { service.get("다", eventId) }
    }

    @Test
    fun `참여자도 정산에 나온 사람도 아닌 uid 는 BAD_SETTLEMENT`() {
        assertClubError("BAD_SETTLEMENT", HttpStatus.BAD_REQUEST) { save(0, listOf(item("밥", 1000, "남", "가" to 1000))) }
        assertClubError("BAD_SETTLEMENT", HttpStatus.BAD_REQUEST) { save(0, listOf(item("밥", 1000, "가", "없는사람" to 1000))) }
        assertNull(heads.findById(eventId).orElse(null))
    }

    // --- version ---

    @Test
    fun `낡은 version 은 SETTLEMENT_CONFLICT 와 최신 정산`() {
        save(0)
        val e = assertClubError("SETTLEMENT_CONFLICT", HttpStatus.CONFLICT) { save(0, uid = "나") }
        val latest = e.extra["settlement"] as SettlementDto
        assertEquals(1, latest.version)
        assertEquals(69000, latest.total)
    }

    @Test
    fun `version 이 uid 검사보다 먼저 — 낡은 화면은 충돌을 받아야 고칠 수 있다`() {
        save(0)
        assertClubError("SETTLEMENT_CONFLICT", HttpStatus.CONFLICT) { save(0, listOf(item("밥", 1000, "남", "가" to 1000))) }
    }

    @Test
    fun `항목을 다 비워도 머리는 남고 version 은 계속 오른다 — 옛 0 이 덮어쓰지 못하게`() {
        save(0)
        val cleared = save(1, emptyList())
        assertEquals(2, cleared.version)
        assertTrue(cleared.items.isEmpty())
        assertNull(events.get("나", eventId).settlementTotal)
        assertClubError("SETTLEMENT_CONFLICT", HttpStatus.CONFLICT) { save(0) }
    }

    // --- 입력 ---

    @Test
    fun `틀린 입력은 전부 BAD_SETTLEMENT — 잭슨이 바꿔 주는 소수·문자열 금액도`() {
        val bad = listOf(
            listOf(item("", 1000, "가", "가" to 1000)),
            listOf(item("가".repeat(21), 1000, "가", "가" to 1000)),
            listOf(item("밥", 0, "가", "가" to 0)),
            listOf(item("밥", 10_000_001, "가", "가" to 10_000_001)),
            listOf(item("밥", 1.5, "가", "가" to 1.5)),
            listOf(item("밥", "1000", "가", "가" to "1000")),
            listOf(item("밥", BigInteger("99999999999999999999"), "가", "가" to 1000)),
            listOf(item("밥", 1000, "가")),                                   // 몫 없음
            listOf(item("밥", 1000, "가", "가" to 500, "가" to 500)),          // 겹침
            listOf(item("밥", 1000, "가", "가" to 500, "나" to 400)),          // 합이 다르다
            listOf(item("밥", 1000, "가", "가" to 1100, "나" to -100)),        // 음수 몫
            listOf(item("밥", 1000, " ", "가" to 1000)),
            List(31) { item("밥", 1000, "가", "가" to 1000) },
        )
        bad.forEachIndexed { i, items -> assertEquals("BAD_SETTLEMENT", assertFailsWith<ClubError>("$i 번") { save(0, items) }.code, "$i 번") }
        assertClubError("BAD_SETTLEMENT", HttpStatus.BAD_REQUEST) { service.save("가", eventId, SettlementInput(null, usual())) }
        assertClubError("BAD_SETTLEMENT", HttpStatus.BAD_REQUEST) { service.save("가", eventId, SettlementInput(-1, usual())) }
        assertClubError("BAD_SETTLEMENT", HttpStatus.BAD_REQUEST) { service.save("가", eventId, SettlementInput(0.0, usual())) }
        assertClubError("BAD_SETTLEMENT", HttpStatus.BAD_REQUEST) { service.save("가", eventId, SettlementInput(0, null)) }
        // 30개 · 0원 몫은 된다. 결제한 사람이 몫에 없어도 된다
        assertEquals(30, save(0, List(30) { item("밥$it", 1000, "가", "나" to 1000, "다" to 0) }).items.size)
    }

    @Test
    fun `지운 일정 · 없는 일정은 NO_SUCH_EVENT`() {
        assertClubError("NO_SUCH_EVENT", HttpStatus.NOT_FOUND) { service.get("가", 999_999) }
        events.delete("가", eventId)
        assertClubError("NO_SUCH_EVENT", HttpStatus.NOT_FOUND) { service.get("가", eventId) }
        assertClubError("NO_SUCH_EVENT", HttpStatus.NOT_FOUND) { save(0) }
    }

    @Test
    fun `정산을 고쳐도 일정 version 은 그대로`() {
        val before = events.get("가", eventId).version
        save(0)
        service.mark("다", eventId, TransferInput("다", "가", true))
        assertEquals(before, events.get("가", eventId).version)
    }

    // --- 보냈어요 ---

    @Test
    fun `보내는 사람이 체크하고 풀 수 있다 — version 은 안 오른다, 두 번 해도 같다`() {
        save(0)
        val s = service.mark("다", eventId, TransferInput("다", "가", true))
        val t = s.transfers.first { it.from.uid == "다" }
        assertTrue(t.paid)
        assertNotNull(t.paidAt)
        assertEquals(1, s.version)
        assertTrue(service.mark("다", eventId, TransferInput("다", "가", true)).transfers.first { it.from.uid == "다" }.paid)
        assertFalse(service.mark("다", eventId, TransferInput("다", "가", false)).transfers.first { it.from.uid == "다" }.paid)
        assertFalse(service.mark("다", eventId, TransferInput("다", "가", false)).transfers.first { it.from.uid == "다" }.paid)
    }

    @Test
    fun `받는 사람 · 만든 사람 · 운영진도 체크하고, 그 밖의 참여자는 NOT_PARTICIPANT`() {
        save(0)
        service.mark("가", eventId, TransferInput("다", "가", true))   // 받는 사람 = 만든 사람
        service.mark("운영", eventId, TransferInput("나", "가", true))
        val e = assertClubError("NOT_PARTICIPANT", HttpStatus.FORBIDDEN) { service.mark("나", eventId, TransferInput("다", "가", false)) }
        assertEquals("보내는 사람, 받는 사람, 만든 사람, 운영진만 체크할 수 있어요.", e.message)
    }

    @Test
    fun `없는 쌍은 NO_SUCH_TRANSFER, 볼 수 없는 사람에게는 그보다 먼저 403`() {
        save(0)
        assertClubError("NO_SUCH_TRANSFER", HttpStatus.NOT_FOUND) { service.mark("가", eventId, TransferInput("가", "다", true)) }
        assertClubError("NOT_PARTICIPANT", HttpStatus.FORBIDDEN) { service.mark("남", eventId, TransferInput("가", "다", true)) }
        assertClubError("BAD_SETTLEMENT", HttpStatus.BAD_REQUEST) { service.mark("다", eventId, TransferInput("다", "가", null)) }
        assertClubError("BAD_SETTLEMENT", HttpStatus.BAD_REQUEST) { service.mark("다", eventId, TransferInput("", "가", true)) }
    }

    @Test
    fun `저장으로 그 쌍의 금액이 바뀌면 체크가 풀리고, 그대로면 남는다`() {
        save(0)
        service.mark("다", eventId, TransferInput("다", "가", true))
        service.mark("나", eventId, TransferInput("나", "가", true))
        // 카페만 바꾼다 — 다→가 20,000 은 그대로, 나→가 는 15,500 → 16,500
        val s = save(1, listOf(usual()[0], item("카페", 7000, "나", "가" to 3500, "나" to 3500)))
        assertEquals(mapOf("다" to true, "나" to false), s.transfers.associate { it.from.uid to it.paid })
        assertEquals(16500, s.transfers.first { it.from.uid == "나" }.amount)
        // 쌍이 사라지면 기록도 지운다 — 다시 생겨도 체크가 되살아나지 않는다
        save(2, listOf(item("방탈출", 60000, "가", "가" to 30000, "나" to 30000)))
        assertTrue(save(3).transfers.none { it.from.uid == "다" && it.paid })
    }

    @Test
    fun `닉네임을 바꿔도 송금 쌍과 체크는 그대로`() {
        // 금액이 같은 두 사람(나·다 각 20,000 빚) — 닉네임으로 줄 세웠다면 이름을 바꾸는 순간 순서가 뒤집힌다
        save(0, listOf(item("방탈출", 60000, "가", "가" to 20000, "나" to 20000, "다" to 20000)))
        service.mark("나", eventId, TransferInput("나", "가", true))
        members.findByUserId("나")!!.nickname = "하하"
        members.findByUserId("다")!!.nickname = "가가"
        val s = service.get("가", eventId)
        assertEquals(listOf("나", "다"), s.transfers.map { it.from.uid })
        assertTrue(s.transfers.first { it.from.uid == "나" }.paid)
    }

    @Test
    fun `보기는 아무것도 바꾸지 않는다`() {
        save(0)
        service.mark("다", eventId, TransferInput("다", "가", true))
        repeat(2) { service.get("가", eventId) }
        assertTrue(service.get("가", eventId).transfers.first { it.from.uid == "다" }.paid)
        assertEquals(1, heads.findById(eventId).get().version)
    }
}
