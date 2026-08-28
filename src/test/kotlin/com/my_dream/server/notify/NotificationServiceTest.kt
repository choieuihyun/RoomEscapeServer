package com.my_dream.server.notify

import com.my_dream.server.domain.NotificationLog
import com.my_dream.server.domain.NotificationLogRepository
import com.my_dream.server.domain.Store
import com.my_dream.server.domain.StoreRepository
import com.my_dream.server.domain.Theme
import com.my_dream.server.domain.ThemeRepository
import com.my_dream.server.domain.TimeSlot
import com.my_dream.server.domain.TimeSlotRepository
import com.my_dream.server.domain.Watch
import com.my_dream.server.domain.WatchRepository
import com.my_dream.server.sync.SlotTransition
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M3 의 심장 — **누구에게 보낼지, 지금 보내도 되는지**.
 *
 * 발송 경로는 가짜로 바꿔 끼운다. 여기서 검증할 것은 판정이지 FCM 이 아니다.
 */
@DataJpaTest
class NotificationServiceTest @Autowired constructor(
    private val stores: StoreRepository,
    private val themes: ThemeRepository,
    private val slots: TimeSlotRepository,
    private val watches: WatchRepository,
    private val logs: NotificationLogRepository,
) {

    /** 보낸 것을 기억만 하는 발송기. [result] 로 결과를 바꿔 끼운다. */
    private class FakeNotifier : Notifier {
        val sent = mutableListOf<Notification>()
        var result = Delivery.DELIVERED
        var explode = false
        override fun send(notification: Notification): Delivery {
            if (explode) throw IllegalStateException("발송 경로가 터졌다")
            sent += notification
            return result
        }
    }

    private val notifier = FakeNotifier()
    private val date: LocalDate = LocalDate.now().plusDays(1)
    private val time: LocalTime = LocalTime.of(19, 35)

    private lateinit var slot: TimeSlot
    private fun service(cooldownMinutes: Long = 60) =
        NotificationService(watches, logs, notifier, cooldownMinutes)

    @BeforeEach
    fun setUp() {
        val store = stores.save(Store("play33-daejeon", "플레이33", "대전점"))
        val theme = themes.save(Theme(store, "34", "우울해서 빵 샀어"))
        slot = slots.save(TimeSlot(theme, date, time, available = true, lastCheckedAt = Instant.now()))
    }

    private fun transition() = SlotTransition(
        timeSlotId = requireNotNull(slot.id),
        storeKey = "play33-daejeon",
        branchName = "대전점",
        themeName = "우울해서 빵 샀어",
        date = date,
        time = time,
    )

    private fun watch(userId: String) = watches.save(Watch(userId, slot, Instant.now()))

    @Test
    fun `감시한 사람에게만 간다`() {
        watch("나")

        val summary = service().onTransitions(listOf(transition()))

        assertEquals(1, summary.sent)
        assertEquals("나", notifier.sent.single().userId)
        assertEquals("대전점", notifier.sent.single().branchName)
    }

    @Test
    fun `아무도 안 보는 자리는 조용하다`() {
        val summary = service().onTransitions(listOf(transition()))

        assertEquals(0, summary.sent)
        assertTrue(notifier.sent.isEmpty())
    }

    @Test
    fun `같은 자리를 여러 사람이 보면 각자에게 간다`() {
        watch("나")
        watch("너")

        val summary = service().onTransitions(listOf(transition()))

        assertEquals(2, summary.sent)
        assertEquals(setOf("나", "너"), notifier.sent.map { it.userId }.toSet())
    }

    @Test
    fun `쿨다운 안에서는 다시 보내지 않는다`() {
        watch("나")
        service().onTransitions(listOf(transition()))

        // 수집이 5분 뒤 또 같은 전이를 물어와도 두 번 울리지 않는다
        val again = service().onTransitions(listOf(transition()))

        assertEquals(0, again.sent)
        assertEquals(1, again.cooled)
        assertEquals(1, notifier.sent.size)
    }

    @Test
    fun `쿨다운이 지나면 다시 보낸다`() {
        val w = watch("나")
        // 1시간 1분 전에 보낸 것으로 꾸민다 — "놓쳤어도 다음 기회" 가 이 서비스의 가치다
        logs.save(NotificationLog(w, Instant.now().minus(Duration.ofMinutes(61)), NotificationLog.SENT))

        val summary = service().onTransitions(listOf(transition()))

        assertEquals(1, summary.sent)
        assertEquals(0, summary.cooled)
    }

    @Test
    fun `발송에 실패하면 쿨다운을 시작하지 않는다`() {
        watch("나")
        notifier.result = Delivery.FAILED
        val first = service().onTransitions(listOf(transition()))
        assertEquals(1, first.failed)

        // 실패를 "알렸다" 로 세면 그 자리는 한 시간 동안 조용히 묻힌다
        notifier.result = Delivery.DELIVERED
        val second = service().onTransitions(listOf(transition()))

        assertEquals(1, second.sent)
        assertEquals(0, second.cooled)
    }

    @Test
    fun `발송기가 예외를 던져도 판정은 계속된다`() {
        watch("나")
        watch("너")
        notifier.explode = true

        val summary = service().onTransitions(listOf(transition()))

        // 한 사람에게 실패했다고 나머지 판정까지 멈추면 안 된다
        assertEquals(2, summary.failed)
        assertEquals(0, summary.sent)
        assertEquals(2, logs.count().toInt())
    }

    @Test
    fun `보낸 기록이 남는다`() {
        watch("나")
        service().onTransitions(listOf(transition()))

        val log = logs.findAll().single()
        assertEquals(NotificationLog.SENT, log.outcome)
    }

    @Test
    fun `전이가 없으면 아무 일도 하지 않는다`() {
        watch("나")

        val summary = service().onTransitions(emptyList())

        assertEquals(0, summary.sent + summary.cooled + summary.failed)
        assertEquals(0, logs.count().toInt())
    }

    @Test
    fun `받을 기기가 없으면 기록도 쿨다운도 남기지 않는다`() {
        watch("나")
        notifier.result = Delivery.NO_ADDRESS

        val summary = service().onTransitions(listOf(transition()))

        // 시도한 적이 없으므로 FAILED 가 아니다. 기록을 남기면 푸시 권한을 안 준 사용자의
        // 감시가 전이마다 실패를 쌓아, 진짜 발송 실패가 그 사이에 묻힌다
        assertEquals(1, summary.unreachable)
        assertEquals(0, summary.failed)
        assertEquals(0, summary.sent)
        assertEquals(0, logs.count().toInt())
    }

    @Test
    fun `기기를 등록하고 나면 그다음 전이부터 바로 간다`() {
        watch("나")
        notifier.result = Delivery.NO_ADDRESS
        service().onTransitions(listOf(transition()))

        // 쿨다운이 시작되지 않았어야 한다 — 시작됐다면 여기서 1시간을 기다리게 된다
        notifier.result = Delivery.DELIVERED
        val summary = service().onTransitions(listOf(transition()))

        assertEquals(1, summary.sent)
        assertEquals(0, summary.cooled)
    }
}
