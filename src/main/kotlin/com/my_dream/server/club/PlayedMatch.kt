package com.my_dream.server.club

import java.text.Normalizer
import java.util.Locale

/**
 * "같은 테마인가" — **판정은 여기 하나뿐이다** (아키텍처 D33).
 *
 * 직접 기록의 멱등 · 일정의 `playedBy` · 협의 도우미 · 자동 기록 건너뛰기가 전부 이 함수를 쓴다.
 * 규칙이 "키가 같거나, **한쪽에 키가 없으면** 정리한 이름이 같음" 이라 이행적이지 않다 —
 * 키 없는 행 하나가 키가 다른 두 행과 동시에 같다. 그래서 DB 의 UNIQUE 로는 못 건다.
 */
object PlayedMatch {

    /**
     * 정리한 이름: NFKC → 소문자 → 글자·숫자만. "비밀의 화원 " 과 "비밀의화원", "ＡＢＣ" 와 "abc" 가 같아진다.
     * **SQL 이 아니라 여기서** 만드는 이유는 Postgres 와 H2 의 정리 규칙이 달라서다.
     * 비어 버리면("!!!", 이모지만) 이름으로는 아무것과도 같지 않다 — [same] 이 빈 이름을 거른다
     */
    fun nameKey(name: String): String {
        val folded = Normalizer.normalize(name, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        return buildString { folded.codePoints().filter(Character::isLetterOrDigit).forEach(::appendCodePoint) }
    }

    fun same(aKey: String?, aNameKey: String, bKey: String?, bNameKey: String): Boolean =
        if (aKey != null && bKey != null) aKey == bKey else aNameKey.isNotEmpty() && aNameKey == bNameKey
}
