package com.my_dream.server.notify

import com.my_dream.server.domain.NotificationLog
import com.my_dream.server.domain.NotificationLogRepository
import com.my_dream.server.domain.WatchRepository
import com.my_dream.server.sync.SlotTransition
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

/**
 * 전이가 났을 때 **누구에게 보낼지, 지금 보내도 되는지**를 판정한다.
 *
 * 발송 자체는 [Notifier] 가 한다. 이 클래스가 어려운 쪽이다 —
 * 잘못 보내면 사용자는 알림을 끄고, 그걸로 이 서비스는 끝난다.
 */
@Service
class NotificationService(
    private val watches: WatchRepository,
    private val logs: NotificationLogRepository,
    private val notifier: Notifier,
    @param:Value("\${notify.cooldown-minutes:60}") private val cooldownMinutes: Long,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * **수집 트랜잭션 밖에서 부른다.** 안에서 부르면 저장이 롤백돼도 알림은 이미 나간 뒤라,
     * 존재한 적 없는 자리를 알린 꼴이 된다. 알림은 취소할 수 없다.
     */
    @Transactional
    fun onTransitions(transitions: List<SlotTransition>): NotifySummary {
        if (transitions.isEmpty()) return NotifySummary(0, 0, 0)

        val bySlotId = transitions.associateBy { it.timeSlotId }
        // 전이 한 건마다 조회하지 않고 한 번에 받는다
        val watching = watches.findByTimeSlotIds(bySlotId.keys)
        if (watching.isEmpty()) return NotifySummary(0, 0, 0)

        val now = Instant.now()
        val cooldown = Duration.ofMinutes(cooldownMinutes)
        var sent = 0
        var cooled = 0
        var failed = 0

        for (watch in watching) {
            val transition = bySlotId[watch.timeSlot.id] ?: continue

            val last = logs.findTopByWatchAndOutcomeOrderBySentAtDesc(watch, NotificationLog.SENT)
            if (last != null && Duration.between(last.sentAt, now) < cooldown) {
                cooled++
                continue
            }

            // 발송 경로가 터져도 다른 사람 알림까지 같이 죽으면 안 된다
            val ok = runCatching {
                notifier.send(
                    Notification(
                        userId = watch.userId,
                        branchName = transition.branchName,
                        themeName = transition.themeName,
                        date = transition.date,
                        time = transition.time,
                    ),
                )
            }.getOrElse {
                log.warn("알림 발송 실패 — 감시 {} : {}", watch.id, it.message)
                false
            }

            logs.save(NotificationLog(watch, now, if (ok) NotificationLog.SENT else NotificationLog.FAILED))
            if (ok) sent++ else failed++
        }

        if (sent + cooled + failed > 0) {
            log.info("알림 판정 — 발송 {}건, 쿨다운으로 보류 {}건, 실패 {}건", sent, cooled, failed)
        }
        return NotifySummary(sent, cooled, failed)
    }
}

/**
 * [cooled] 는 "취소표가 났지만 최근에 이미 알려서 참았다" 는 뜻이다.
 * 0 이 아닌 게 정상이고, 이 값이 계속 크면 쿨다운이 너무 짧다는 신호다.
 */
data class NotifySummary(val sent: Int, val cooled: Int, val failed: Int)
