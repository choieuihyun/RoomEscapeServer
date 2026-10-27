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

    /** 동호회 알림 (D34). 문구는 부르는 쪽이 정해 온다 */
    fun push(push: Push): PushResult
}

/**
 * 동호회 알림 한 건 (아키텍처 D34). 빈자리 알림([Notification])과 달리 **제목·본문이 정해져 온다** —
 * 알림 종류가 셋(전날 · 마감 · 예약 완료)에 테스트까지라, 문구를 발송 쪽에 두면 발송 쪽이 동호회를 알아야 한다.
 * [linkFragment] 는 앱 주소 뒤에 붙는다 (`#e=12` → 그 일정).
 * [tag] 가 있으면 **같은 tag 의 알림이 기기에서 하나로 겹친다**(웹 푸시 `tag` + `renotify`) — 일정 댓글(§4.16)이 쓴다.
 * 없으면 알림마다 따로 뜬다(지금까지의 알림 그대로)
 */
data class Push(val userId: String, val title: String, val body: String, val linkFragment: String = "", val tag: String? = null)

/** 기기 수 · 전해진 수 · 다시 시도할 실패 수. 테스트 알림은 이 셋을 그대로 화면에 보여 준다 */
data class PushResult(val devices: Int, val delivered: Int, val failed: Int) {
    /** 빈자리 알림과 같은 세 갈래 (D17) — 기록할지 · 다시 시도할지를 이걸로 정한다 */
    val delivery: Delivery
        get() = when {
            delivered > 0 -> Delivery.DELIVERED
            failed > 0 -> Delivery.FAILED
            // 기기가 없거나, 있던 토큰이 전부 죽어서 방금 다 지웠다
            else -> Delivery.NO_ADDRESS
        }
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

    override fun push(push: Push): PushResult {
        log.info("🔔 동호회 알림 — {} / {} {} → 사용자 {}", push.title, push.body, push.linkFragment, push.userId)
        // send 와 맞춘다 — 개발 중에도 "보냈다" 기록이 쌓여야 판정을 눈으로 확인할 수 있다
        return PushResult(devices = 1, delivered = 1, failed = 0)
    }
}
