package com.my_dream.server.crawler.sinbiweb

import com.my_dream.server.crawler.StoreRef

/**
 * 신비웹(`(주)신비웹`)이 만든 **GET 세대** 예약 사이트 하나 (아키텍처 D41).
 *
 * ```
 * GET /layout/res/home.php?go=rev.make&rev_days=YYYY-MM-DD[&s_zizum=N]
 *   → 지점 하루치 전 테마 (theme_box · h3_theme · ul.reserve_Time li a)
 * ```
 *
 * **매장을 더하려면 [SinbiwebSites.ALL] 에 한 줄을 더한다.** 어댑터 빈은 [SinbiwebAdapterRegistrar] 가 만든다.
 * 그 전에 조사 문서에 먼저 적고, 그 매장 응답을 픽스처로 넣어 `SinbiwebParserTest` 에 한 줄을 더한다 —
 * 공통 파서라 **한 매장을 위해 고친 것이 다른 매장을 조용히 깨뜨릴 수 있다.** 그걸 매장별 픽스처가 잡는다.
 *
 * 같은 신비웹이어도 **매장마다 갈리는 것은 셋이었다**(2026-10-21 강남 4곳 조사). 그 셋만 설정으로 받는다.
 *
 * | | 지점 되읽기 | 테마 번호 | 괄호 |
 * |---|---|---|---|
 * | Studio ESC | 없음 (단일 지점) | 드롭다운 = 이름 | 영문 부제 — 이름에 남는다 |
 * | 돌로이스케이프 | hidden `s_zizum` | 드롭다운 = 장르 뗀 이름 | 장르 (안에 괄호가 또 있다) |
 * | 이룸에이트 | 없음 | 드롭다운 = 장르 뗀 이름 | 장르 |
 * | 황금열쇠 | `select[name=s_zizum]` | 드롭다운이 없다 → 이름이 키 | 장르 |
 *
 * 괄호는 설정이 아니라 **마크업 한 규칙**으로 갈린다 — 장르는 늘 "공백 두 칸 + 괄호" 로 붙고,
 * 영문 부제는 공백 한 칸이다 ([SinbiwebParser.splitNameAndGenre]).
 *
 * ⚠️ **기존 신비웹 넷(포인트나인·비트포비아·제로월드·다이아에그)은 여기로 옮기지 않았다.** 지금 잘 돌고 있고,
 * 옮기려면 이 파서가 그 매장 픽스처에서 **같은 결과**를 내는 것부터 확인해야 한다. 제로월드는 세대가 아예 다르다(조각 · 테마마다 요청).
 */
data class SinbiwebSite(
    /** 사람이 읽는 매장 이름 (`/api/branches` 의 store) */
    val brand: String,
    val host: String,
    /** 황금열쇠는 `http://` 로만 확인했다. https 를 시험하지 않았으므로 바꾸지 않는다 */
    val scheme: String = "https",
    val branches: List<SinbiwebBranch>,
    /** 응답이 어느 지점인지 되읽는 자리. 단일 지점 매장은 [BranchReadback.NONE] */
    val branchReadback: BranchReadback = BranchReadback.NONE,
    /** 오늘부터 며칠치가 열리나. 강남 넷은 +6 까지 열리고 +7 은 "예약가능일이 아닙니다" 였다 (11:16 실측). 홍대엔 2주짜리가 있다 */
    val openDays: Int = 7,
) {
    val baseUrl: String get() = "$scheme://$host"

    init {
        require(branches.isNotEmpty()) { "$brand — 지점이 없다" }
        require(branchReadback == BranchReadback.NONE || branches.all { it.zizum != null }) {
            "$brand — 지점을 되읽는 매장이면 지점마다 s_zizum 값이 있어야 한다"
        }
        require(branches.size == 1 || branchReadback != BranchReadback.NONE) {
            // 지점이 둘 이상인데 되읽지 않으면, s_zizum 이 무시될 때 모든 지점이 같은 데이터로 덮인다
            "$brand — 지점이 여럿이면 응답 지점을 되읽어야 한다"
        }
    }
}

/**
 * @property zizum `s_zizum` 값. 단일 지점 매장은 null — 파라미터를 아예 안 보낸다
 * @property key 서버 지점 키. 한 번 정하면 바꾸지 않는다 (감시·해 본 테마가 이 키로 이어진다)
 */
data class SinbiwebBranch(val zizum: Int?, val branchName: String, val key: String)

enum class BranchReadback {
    /** 지점 필드가 없다 — 단일 지점 매장 */
    NONE,

    /** `<input type=hidden name=s_zizum value='1'>` — ⚠️ 보낸 값을 그대로 돌려줄 뿐이다. 없는 지점도 통과한다 */
    HIDDEN,

    /** `<select name=s_zizum><option value='5' selected>` */
    SELECT,
}

/** 수집 대상 신비웹 GET 세대 매장. **여기에 한 줄 = 매장 하나** */
object SinbiwebSites {

    val STUDIO_ESC = SinbiwebSite(
        brand = "Studio ESC",
        host = "studioesc.co.kr",
        branches = listOf(SinbiwebBranch(null, "강남점", "studioesc-gangnam")),
    )

    /** ⚠️ 포인트나인과 같은 방화벽(nesolution)이 붙어 있다. 막히면 우회하지 않고 뺀다 */
    val DOLO = SinbiwebSite(
        brand = "돌로이스케이프",
        host = "doloescape.co.kr",
        branches = listOf(SinbiwebBranch(1, "강남점", "dolo-gangnam")),
        branchReadback = BranchReadback.HIDDEN,
    )

    val EROOM8 = SinbiwebSite(
        brand = "이룸에이트",
        host = "eroom8.co.kr",
        branches = listOf(SinbiwebBranch(null, "강남점", "eroom8-gangnam")),
    )

    /**
     * 지점이 여섯이다(대구 둘 · 강남 둘 · 건대 · 천안). **강남 둘만 넣었다** — 이번 범위가 강남이다.
     * 번호가 띄엄띄엄(1·11·5·6·7·12)이라 차례로 돌리면 안 된다. **지점을 빼고 부르면 대구 본점이 답한다.**
     */
    val GOLDKEY = SinbiwebSite(
        brand = "황금열쇠",
        host = "xn--jj0b998aq3cptw.com",
        scheme = "http",
        branches = listOf(
            SinbiwebBranch(5, "강남점(타임스퀘어)", "goldkey-gangnam-timesquare"),
            SinbiwebBranch(6, "강남점(플라워로드)", "goldkey-gangnam-flowerroad"),
        ),
        branchReadback = BranchReadback.SELECT,
    )

    // ── 홍대 (2026-10-22 조사) — 셋 다 단일 지점 · 장르 괄호 없음 · 드롭다운 라벨 = 이름 ──

    /** 창이 2주다 — +13 까지 열리고 +14 는 "예약가능일이 아닙니다" (10:14 실측). 휴무는 정기가 아니라 날마다 다르다(10-28 수 휴무 · 11-04 수 영업) */
    val SPEAKEASY = SinbiwebSite(
        brand = "스피키지",
        host = "speakeasyescape.co.kr",
        branches = listOf(SinbiwebBranch(null, "홍대점", "speakeasy-hongdae")),
        openDays = 14,
    )

    /** ⚠️ nesolution 방화벽이 있다(robots 를 부르면 방화벽 404). 막히면 우회하지 않고 뺀다 */
    val WEYOL = SinbiwebSite(
        brand = "위욜",
        host = "weyol.com",
        branches = listOf(SinbiwebBranch(null, "홍대점", "weyol-hongdae")),
    )

    /** ⚠️ nesolution 방화벽 · 창 2주(+13 까지 · 10:17 실측) */
    val HAPPYANDING = SinbiwebSite(
        brand = "해피앤딩",
        host = "happyanding.co.kr",
        branches = listOf(SinbiwebBranch(null, "홍대점", "happyanding-hongdae")),
        openDays = 14,
    )

    val ALL = listOf(STUDIO_ESC, DOLO, EROOM8, GOLDKEY, SPEAKEASY, WEYOL, HAPPYANDING)
}

fun SinbiwebBranch.toStoreRef(site: SinbiwebSite) = StoreRef(key = key, brand = site.brand, branchName = branchName)
