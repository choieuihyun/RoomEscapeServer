package com.my_dream.server.notify

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.LocalTime

/**
 * 보낼 알림 한 건. **어느 경로로 나가든 모양은 같다.**
 *
 * 화면에 쓸 문구는 여기서 만들지 않는다. 무엇이 일어났는지만 담고,
 * 어떻게 보일지는 각 [Notifier] 가 정한다.
 */
data class Notification(
    val userId: String,
    val branchName: String,
    val themeName: String,
    val date: LocalDate,
    val time: LocalTime,
)

/**
 * 발송 한 건의 결과. **세 갈래여야 한다** — `Boolean` 이면 [NO_ADDRESS] 가 [FAILED] 에 섞인다.
 *
 * 섞이면 이렇게 된다: 감시는 걸어 뒀지만 푸시 권한을 준 적이 없는 사용자는
 * 그 자리가 풀릴 때마다 영원히 `FAILED` 기록을 남긴다. 쿨다운은 `SENT` 만 세므로
 * **쿨다운도 시작되지 않아 매 바퀴 재시도된다.** 고장이 아닌데 고장처럼 쌓인다.
 */
enum class Delivery {
    /** 최소 한 대에 전달됐다. 쿨다운이 시작된다 */
    DELIVERED,

    /** 보낼 곳은 있었는데 실패했다 (네트워크·5xx·토큰 갱신 실패). **다음 바퀴에 다시 시도한다** */
    FAILED,

    /**
     * 보낼 곳 자체가 없다 — 기기를 등록한 적이 없거나, 있던 토큰이 전부 죽어서 방금 지웠다.
     * **시도가 아니라 설정 상태다.** 기록도 남기지 않고 쿨다운도 시작하지 않는다
     */
    NO_ADDRESS,
}

/**
 * 알림을 실제로 내보내는 곳.
 *
 * **인터페이스로 두는 이유:** FCM 은 파이어베이스 서비스 계정 키가 있어야 붙는데,
 * 그게 없다고 알림 로직 전체를 못 만들 이유는 없다. 로그로 먼저 완성하고
 * 발송 경로만 나중에 갈아 끼운다 — 판정(누구에게·언제 보낼지)이 진짜 어려운 부분이고,
 * 그건 키 없이도 전부 검증할 수 있다.
 */
interface Notifier {
    fun send(notification: Notification): Delivery
}

/**
 * 기본 발송 경로 — 로그에 남긴다.
 *
 * `notify.channel=fcm` 으로 바꾸기 전까지 이게 쓰인다.
 * 개발 중에는 이쪽이 오히려 낫다: 진짜 푸시가 안 가서 시끄럽지 않고, 판정 결과는 그대로 보인다.
 */
@Component
@ConditionalOnProperty(prefix = "notify", name = ["channel"], havingValue = "log", matchIfMissing = true)
class LoggingNotifier : Notifier {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun send(notification: Notification): Delivery {
        log.info(
            "🔔 알림 — {} / {} {} {} → 사용자 {}",
            notification.branchName, notification.themeName,
            notification.date, notification.time, notification.userId,
        )
        return Delivery.DELIVERED
    }
}
