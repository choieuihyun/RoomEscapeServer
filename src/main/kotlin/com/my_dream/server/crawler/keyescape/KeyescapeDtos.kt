package com.my_dream.server.crawler.keyescape

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/** `run_proc.php` 는 뭘 물어도 이 껍데기로 답한다. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class KeyescapeEnvelope<T>(
    val status: Boolean = false,
    val msg: String? = null,
    val data: T? = null,
)

/**
 * `t=get_theme_info_list` 의 항목.
 *
 * ⚠️ [infoNum] 과 [themeNum] 은 **다른 ID 공간인데 둘 다 작은 정수다.**
 * 섞어 보내면 에러가 아니라 엉뚱한 테마가 그럴듯하게 온다 —
 * 그래서 따로 들고 다니지 않고 **항상 짝으로** 옮긴다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class KeyescapeThemeRow(
    @param:JsonProperty("info_num") val infoNum: Int,
    @param:JsonProperty("theme_num") val themeNum: Int,
    @param:JsonProperty("info_name") val infoName: String,
)

/** `t=get_theme_date&num={infoNum}` — 이름과 달리 **테마 상세**를 준다. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class KeyescapeThemeDetail(
    @param:JsonProperty("name") val name: String? = null,
    @param:JsonProperty("genre") val genre: String? = null,
    /** 난이도. 문자열로 온다(`"4"`). 플레이33처럼 `"4.5"` 가 올 수 있다고 보고 실수로 읽는다 */
    @param:JsonProperty("level") val level: String? = null,
    /** `"75분"` — 단위가 붙어 있다 */
    @param:JsonProperty("play_time") val playTime: String? = null,
    @param:JsonProperty("image_url") val imageUrl: String? = null,
)

/** `t=get_theme_time` 의 항목. [enable] 이 `Y`/`N` 이고 그게 예약 가능 여부다. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class KeyescapeSlotRow(
    /** 응답이 어느 테마 것인지. **요청한 값과 다르면 ID 를 잘못 보낸 것이다** */
    @param:JsonProperty("theme_num") val themeNum: Int? = null,
    @param:JsonProperty("hh") val hh: String,
    @param:JsonProperty("mm") val mm: String,
    @param:JsonProperty("enable") val enable: String? = null,
)
