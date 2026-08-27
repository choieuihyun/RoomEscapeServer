package com.my_dream.server.sync

import com.my_dream.server.crawler.play33.Play33Branch
import com.my_dream.server.crawler.play33.Play33Crawler
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * 플레이33 **한 지점·하루**를 손으로 수집한다. 확인용이다.
 *
 * 한 바퀴 도는 것은 [StoreCollector] 가 어댑터를 통해 한다.
 * 여기는 "이 지점 이 날짜만 지금 당장" 을 눌러 보는 자리라 어댑터를 거치지 않는다.
 */
@Component
class Play33Collector(
    private val crawler: Play33Crawler,
    private val ingest: ScheduleIngest,
) {

    fun collectOne(branch: Play33Branch, date: LocalDate): SyncResult =
        ingest.ingest(crawler.fetch(branch, date))
}
