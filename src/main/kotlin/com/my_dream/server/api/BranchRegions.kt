package com.my_dream.server.api

/**
 * 지점이 어느 지역인가 — `/api/branches` 의 `region` (Floduler 계약 §4.4 "지역 `region`").
 *
 * **왜 서버가 주나.** 앱이 지점 이름에 든 낱말("강남"·"서면")로 추측해 왔는데, 이름에 지역이 안 든
 * 지점이 많다 — 키이스케이프 LOG_IN·STATION·후즈데어는 전부 강남인데 앱에서 '기타' 로 떠 있었다.
 * 매장이 60~70곳으로 늘면 추측 표가 앱에서 계속 자란다. 지점을 넣는 쪽이 지역도 같이 아는 게 맞다.
 *
 * **왜 어댑터 enum 이 아니라 여기인가.** 수집기는 지역을 쓰지 않는다. `StoreRef` 에 넣으면
 * 크롤 결과(`DaySchedule`)까지 화면용 값을 들고 다니게 된다. 화면에만 쓰는 값은 API 옆에 둔다.
 *
 * **지원 지점은 전부 여기 키가 있어야 한다** — `BranchRegionsTest` 가 막는다. 지역을 모르면
 * 키를 빼지 말고 `null` 로 둔다. 빼먹은 것과 "모른다" 를 가르기 위해서다. 빼먹으면 새 지점이
 * 조용히 null 로 나가 앱의 이름 추측으로 떨어지는데, 그게 바로 고치려던 문제다.
 */
object BranchRegions {

    /**
     * 앱이 받는 값 전부. **원본은 앱의 `REGIONS`**(`app/src/regions.ts`)다.
     * 목록 밖 값은 앱이 null 처럼 다루므로, 지역을 새로 만들려면 **앱 목록에 먼저 더하고** 여기에 더한다.
     * 앱의 '기타' 는 보내지 않는다 — 모르면 null 이고, null 을 '기타' 로 보일지는 앱이 정한다.
     */
    val ALLOWED = setOf("강남", "홍대", "건대", "부산", "대구", "대전", "수원", "김포", "용인")

    /**
     * 근거: 지점 이름 · 플레이방탈 지역 목록(2026-10-21) · 조사 문서 주소.
     * null 은 주소를 확인하지 못한 지점이다 ([매장 정보.md] "그 밖").
     */
    private val byKey: Map<String, String?> = mapOf(
        // 강남 — 키이스케이프 넷(LOG_IN·STATION·후즈데어·메모리컴퍼니)은 이름에 지역이 없다. 플레이방탈 강남 목록으로 확인
        "keyescape-gangnam" to "강남",
        "keyescape-theoreum" to "강남",
        "keyescape-station" to "강남",
        "keyescape-uzulike" to "강남",
        "keyescape-login1" to "강남",
        "keyescape-login2" to "강남",
        "keyescape-memory" to "강남",
        "keyescape-whosthere" to "강남",
        "bitphobia-gangnam" to "강남",
        "bitphobia-gangnam2" to "강남",
        "bitphobia-luna" to "강남",
        "bitphobia-stella" to "강남",
        "pointnine-gangnam" to "강남",
        "zeroworld-gangnam" to "강남",
        "playpointlab-gangnam" to "강남",
        // 신비웹 공통 어댑터 (D41) — 플레이방탈 강남 목록
        "studioesc-gangnam" to "강남",
        "dolo-gangnam" to "강남",
        "eroom8-gangnam" to "강남",
        "goldkey-gangnam-timesquare" to "강남",
        "goldkey-gangnam-flowerroad" to "강남",
        "dpsnnn-gangnam" to "강남", // 아임웹 (D41)
        "codek-gangnam" to "강남", // ASP 예약 솔루션 (D41)
        "signescape-gangnamcity" to "강남",
        "thefrank-gangnam" to "강남", // 신비웹 AJAX 세대 (D41)

        "keyescape-hongdae" to "홍대",
        "bitphobia-hongdae" to "홍대",
        "bitphobia-hongdae3" to "홍대",
        "play33-hongdae" to "홍대",
        "jigubyeol-hongdae-adventure" to "홍대",
        "jigubyeol-hongdae-lastcity" to "홍대",
        "pointnine-hongdae" to "홍대",
        "zeroworld-hongdae" to "홍대",
        "rabbithole-hongdae" to "홍대",

        "play33-konkuk" to "건대",
        "pointnine-konkuk" to "건대",
        "zeroworld-dive-konkuk" to "건대",

        "keyescape-busan" to "부산",
        "bitphobia-seomyeon" to "부산",
        "bitphobia-seomyeon-red" to "부산",
        "playpointlab-seomyeon-origin" to "부산",
        "playpointlab-tantan" to "부산",
        "playpointlab-haeundae" to "부산",
        "diaegg-seomyeon" to "부산",

        "jigubyeol-daegu" to "대구",
        "play33-daejeon" to "대전",
        "play33-suwon" to "수원",
        "zeroworld-gimpo" to "김포",
        "keyescape-everland" to "용인",

        // 주소 미확인 — 같은 다이아에그 사이트지만 미스테리인이 서면에 있는지는 안 봤다
        "diaegg-mysteryin" to null,
        "bitphobia-dungeon101" to null,
    )

    fun of(branchKey: String): String? = byKey[branchKey]

    fun isListed(branchKey: String): Boolean = byKey.containsKey(branchKey)

    val keys: Set<String> get() = byKey.keys
}
