package com.my_dream.server.club

import com.my_dream.server.notify.Notifier
import com.my_dream.server.notify.Push
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

data class PushTestDto(val devices: Int, val delivered: Int, val failed: Int)

/**
 * "테스트 알림 보내기" (계약 §4.10). 부른 사람 기기에만, **사람마다 1분에 한 번** — 막지 않으면 버튼 연타가 그대로 FCM 으로 간다.
 * 승인 멤버가 아니어도 된다(로그인만) — 알림이 오는지 확인하는 데 동호회 자격은 필요 없다. 제한은 메모리라 재시작하면 풀린다(계약)
 */
@Service
class PushTestService(private val notifier: Notifier) {

    private val lastSent = ConcurrentHashMap<String, Instant>()

    fun send(uid: String, now: Instant = Instant.now()): PushTestDto {
        var ok = false
        lastSent.compute(uid) { _, prev ->
            if (prev == null || Duration.between(prev, now) >= GAP) {
                ok = true
                now
            } else {
                prev
            }
        }
        if (!ok) throw ClubError.tooSoon()
        val r = notifier.push(Push(uid, "테스트 알림이에요", "이 기기로 동호회 알림을 받을 수 있어요."))
        return PushTestDto(r.devices, r.delivered, r.failed)
    }

    companion object {
        val GAP: Duration = Duration.ofMinutes(1)
    }
}
