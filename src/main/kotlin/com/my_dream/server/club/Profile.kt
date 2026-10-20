package com.my_dream.server.club

import com.my_dream.server.domain.MemberProfile
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * 받는 모양. **타입을 느슨하게 둔다** (아키텍처 D35) — 정수 칸에 `"abc"` 가 오면 JSON 단계에서 깨져
 * 계약의 `BAD_PROFILE` 대신 스프링 기본 400 이 나가고, 잭슨은 `1.5` 를 1 로 조용히 바꾼다.
 * 그래서 [roomCount] · [genres] 는 받은 그대로 두고 [ProfileRules] 가 본다
 */
data class ProfileInput(
    val realName: String? = null,
    val birthDate: String? = null,
    val mbti: String? = null,
    val roomCount: Any? = null,
    val genres: List<Any?>? = null,
    val lifeTheme: LifeThemeInput? = null,
)

data class LifeThemeInput(val themeName: String? = null, val branchName: String? = null, val themeKey: String? = null)

/** 내보내는 모양. 계약은 Floduler `작업명세서.md` §4.11 `ProfileDto` */
data class ProfileDto(
    val realName: String,
    val birthDate: LocalDate,
    val mbti: String,
    val roomCount: Int,
    val genres: List<String>,
    val lifeTheme: LifeThemeDto,
)

data class LifeThemeDto(val themeName: String, val branchName: String?, val themeKey: String?)

fun MemberProfile.toDto() = ProfileDto(
    realName = realName,
    birthDate = birthDate,
    mbti = mbti,
    roomCount = roomCount,
    genres = genres.split(GENRE_SEPARATOR),
    lifeTheme = LifeThemeDto(lifeThemeName, lifeThemeBranch, lifeThemeKey),
)

/** 저장에 쓸 수 있게 검사를 마친 프로필 */
data class CleanProfile(
    val realName: String,
    val birthDate: LocalDate,
    val mbti: String,
    val roomCount: Int,
    val genres: List<String>,
    val lifeThemeName: String,
    val lifeThemeBranch: String?,
    val lifeThemeKey: String?,
) {
    fun applyTo(row: MemberProfile, now: Instant) = row.apply {
        realName = this@CleanProfile.realName
        birthDate = this@CleanProfile.birthDate
        mbti = this@CleanProfile.mbti
        roomCount = this@CleanProfile.roomCount
        genres = this@CleanProfile.genres.joinToString(GENRE_SEPARATOR)
        lifeThemeName = this@CleanProfile.lifeThemeName
        lifeThemeBranch = this@CleanProfile.lifeThemeBranch
        lifeThemeKey = this@CleanProfile.lifeThemeKey
        updatedAt = now
    }

    fun newRow(userId: String, now: Instant) = MemberProfile(
        userId, realName, birthDate, mbti, roomCount, genres.joinToString(GENRE_SEPARATOR),
        lifeThemeName, lifeThemeBranch, lifeThemeKey, now,
    )
}

/** 장르 이름에 쉼표가 없어서 구분자로 쓴다. 장르를 더할 때 쉼표가 든 이름은 안 된다 */
private const val GENRE_SEPARATOR = ","

/**
 * 프로필 칸 검사. **트랜잭션 밖에서** 부른다 — 틀린 입력 때문에 멤버 행을 잠글 일이 없게 (`cleanNickname` 과 같다).
 *
 * 글자 수는 전부 **코드 포인트**로 센다. `String.length` 는 이모지를 둘로 세는데 Postgres `VARCHAR` 는 하나로 센다 —
 * 둘이 다르면 "서버는 통과시켰는데 DB 가 거절" 하는 500 이 생긴다.
 */
object ProfileRules {

    val MBTI = setOf(
        "ISTJ", "ISFJ", "INFJ", "INTJ", "ISTP", "ISFP", "INFP", "INTP",
        "ESTP", "ESFP", "ENFP", "ENTP", "ESTJ", "ESFJ", "ENFJ", "ENTJ", "UNKNOWN",
    )

    /**
     * **더하기는 되고 빼기는 안 된다.** 뺀 장르를 저장해 둔 사람이 프로필을 다시 저장하면 400 이 난다 —
     * 빼려면 저장된 값부터 옮긴다 (§4.11). 더할 때는 서버를 앱보다 먼저 올린다
     */
    val GENRES = setOf("공포", "스릴러", "추리", "미스터리", "감성", "드라마", "코믹", "판타지", "SF", "어드벤처", "잠입", "액션", "기타")

    val OLDEST_BIRTH: LocalDate = LocalDate.of(1940, 1, 1)
    const val NAME_MAX = 20
    const val ROOM_MAX = 9999L
    const val GENRES_MAX = 5

    fun clean(p: ProfileInput, today: LocalDate): CleanProfile {
        val name = p.realName?.trim().orEmpty()
        if (name.letters() !in 1..NAME_MAX) throw ClubError.badProfile("이름은 1자에서 20자 사이로 적어 주세요.")

        val birth = try {
            // ISO 형식은 엄격하다 — 2월 30일, 평년의 2월 29일은 여기서 걸린다
            LocalDate.parse(p.birthDate ?: "")
        } catch (e: DateTimeParseException) {
            throw ClubError.badProfile("생일을 다시 확인해 주세요.")
        }
        if (birth < OLDEST_BIRTH || birth > today) throw ClubError.badProfile("생일을 다시 확인해 주세요.")

        val mbti = p.mbti?.takeIf { it in MBTI } ?: throw ClubError.badProfile("MBTI 를 다시 골라 주세요.")

        // 1.5 는 Double, "12" 는 String 으로 온다 — 둘 다 거절한다 ([wholeNumber])
        val rooms = wholeNumber(p.roomCount) ?: -1
        if (rooms !in 0..ROOM_MAX) throw ClubError.badProfile("방 수는 0에서 9999 사이 숫자로 적어 주세요.")

        val genres = p.genres.orEmpty()
        if (genres.size !in 1..GENRES_MAX || genres.any { it !is String || it !in GENRES } || genres.toSet().size != genres.size) {
            throw ClubError.badProfile("좋아하는 장르를 1개에서 5개까지 골라 주세요.")
        }

        val life = p.lifeTheme ?: throw ClubError.badProfile("인생 테마를 적어 주세요.")
        val themeName = life.themeName?.trim().orEmpty()
        // "!!!" 나 이모지만 있는 이름은 정리하면 빈다 — 해 본 테마와 같은 규칙 (D33)
        if (themeName.letters() !in 1..40 || PlayedMatch.nameKey(themeName).isEmpty()) {
            throw ClubError.badProfile("인생 테마 이름을 확인해 주세요.")
        }

        return CleanProfile(
            realName = name,
            birthDate = birth,
            mbti = mbti,
            roomCount = rooms.toInt(),
            genres = genres.map { it as String },
            lifeThemeName = themeName,
            lifeThemeBranch = optional(life.branchName, 40),
            lifeThemeKey = optional(life.themeKey, 80),
        )
    }

    private fun String.letters(): Int = codePointCount(0, length)

    private fun optional(raw: String?, max: Int): String? {
        val s = raw?.trim()?.ifEmpty { null } ?: return null
        if (s.letters() > max) throw ClubError.badProfile("인생 테마 이름을 확인해 주세요.")
        return s
    }
}
