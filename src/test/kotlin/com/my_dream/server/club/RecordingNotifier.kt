package com.my_dream.server.club

import com.my_dream.server.notify.Notification
import com.my_dream.server.notify.Notifier
import com.my_dream.server.notify.Push
import com.my_dream.server.notify.PushResult
import java.util.concurrent.CopyOnWriteArrayList

/** 동호회 알림 테스트용 — 보낸 것을 모으고, 사람마다 결과를 정할 수 있다(기본: 기기 하나에 전해짐) */
class RecordingNotifier : Notifier {
    val pushes = CopyOnWriteArrayList<Push>()
    val results = mutableMapOf<String, PushResult>()

    override fun send(notification: Notification) = error("동호회 알림은 send 를 쓰지 않는다")

    override fun push(push: Push): PushResult {
        pushes += push
        return results[push.userId] ?: PushResult(1, 1, 0)
    }
}
