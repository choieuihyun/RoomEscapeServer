package com.my_dream.server.club

import com.my_dream.server.domain.ClubMember
import com.my_dream.server.domain.ClubMemberRepository
import com.my_dream.server.domain.DeviceTokenRepository
import com.my_dream.server.domain.KakaoLinkRepository
import com.my_dream.server.domain.MemberProfileRepository
import com.my_dream.server.domain.MemberRole
import com.my_dream.server.domain.MemberStatus
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import java.time.Instant

/**
 * 내 동호회 상태. [status] 가 `NONE` 이면 멤버 행이 없다 — 로그인만 하고 가입 신청을 안 했다.
 * 모양은 Floduler `작업명세서.md` §4.6 `MeDto`.
 */
data class MeDto(
    val uid: String,
    val nickname: String?,
    val role: String?,
    val status: String,
    val kakaoLinked: Boolean,
    /** 아직 안 채운 기존 멤버 · NONE 은 null (§4.11) */
    val profile: ProfileDto? = null,
)

/** 관리 화면의 한 줄. [hasDevice] 는 "알림을 받을 기기가 하나라도 있나" — 없으면 전날 알림이 안 간다 */
data class MemberDto(
    val uid: String,
    val nickname: String,
    val role: String,
    val status: String,
    val createdAt: Instant,
    val decidedAt: Instant?,
    val hasDevice: Boolean,
    val profile: ProfileDto? = null,
)

/**
 * 가입 · 승인 · 역할 (아키텍처 D28). 상태 전이는 Floduler `작업명세서.md` §4.6 "멤버 상태 전이" 표다.
 *
 * **관리 작업은 전부 같은 순서로 잠근다** — ① 승인된 관리자 행 전부 ② 대상 한 줄.
 * - ①을 잠그는 이유는 "마지막 관리자" 판정이다. 대상만 잠그면 두 관리자가 동시에 서로를 내릴 때
 *   둘 다 "관리자 둘" 을 보고 통과해서 0명이 된다 (`MembershipConcurrencyTest`)
 * - **순서가 늘 같아야** 교착이 안 생긴다. 관리 작업끼리는 ①에서 줄을 서고, 가입(대상 한 줄만 잠금)과는 고리가 안 생긴다
 * - 그래서 관리 작업은 사실상 한 번에 하나씩 돈다. 관리자가 몇 명인 동호회에서는 아무 문제가 없다
 *
 * 관리자 확인이 **대상 확인보다 먼저**다. 반대면 관리자가 아닌 사람이 404 와 다른 답의 차이로
 * 남의 가입 여부를 알아낼 수 있다.
 */
@Service
class MembershipService(
    private val members: ClubMemberRepository,
    private val kakaoLinks: KakaoLinkRepository,
    private val devices: DeviceTokenRepository,
    private val profiles: MemberProfileRepository,
    transactionManager: PlatformTransactionManager,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val tx = TransactionTemplate(transactionManager)

    @Transactional(readOnly = true)
    fun me(uid: String): MeDto {
        val m = members.findByUserId(uid)
        return MeDto(
            uid = uid,
            nickname = m?.nickname,
            role = m?.role?.name,
            status = m?.status?.name ?: NONE,
            kakaoLinked = kakaoLinks.existsByUserId(uid),
            profile = m?.let { profiles.findByUserId(uid)?.toDto() },
        )
    }

    /**
     * 가입 신청. **여러 번 눌러도 사람은 하나다.**
     *
     * 동시에 두 번 오면(두 탭, 빠른 더블클릭) 둘 다 "아직 없다" 를 보고 INSERT 해서 하나는 UNIQUE 에 걸린다.
     * 걸린 쪽을 500 으로 내보내지 않고 **새 트랜잭션에서 한 번 더** 돈다 — 그때는 행이 있어서 갱신 경로로 간다.
     * 같은 트랜잭션에서 다시 읽을 수는 없다: Postgres 는 제약 위반이 난 트랜잭션을 통째로 못 쓰게 만든다.
     */
    /**
     * [profile] 은 **선택이다** (§4.11) — 서버가 앱보다 먼저 올라가는 동안 옛 앱(프로필을 모름)의 가입 신청이
     * 막히면 안 된다. 필수는 화면이 강제한다. 보냈으면 다 검사한다
     */
    fun join(uid: String, nickname: String, profile: ProfileInput? = null): MeDto {
        val name = cleanNickname(nickname)
        val clean = profile?.let { ProfileRules.clean(it, todayKst()) }
        return try {
            tx.execute { joinOnce(uid, name, clean) }!!
        } catch (e: DataIntegrityViolationException) {
            log.info("가입 신청이 동시에 두 번 왔다 — 다시 읽어서 처리한다 ({})", uid)
            tx.execute { joinOnce(uid, name, clean) }!!
        }
    }

    private fun joinOnce(uid: String, name: String, profile: CleanProfile?): MeDto {
        val existing = members.findForUpdateByUserId(uid)
        when (existing?.status) {
            null -> members.saveAndFlush(ClubMember(uid, name, MemberRole.MEMBER, MemberStatus.PENDING, Instant.now()))
            // 내보낸 사람도 다시 신청한다 — 들어오려면 운영진이 다시 승인해야 한다 (2026-10-20 사용자 결정 · D28 정정)
            MemberStatus.REJECTED, MemberStatus.REMOVED -> existing.apply {
                this.nickname = name
                status = MemberStatus.PENDING
                // 내보내기가 이미 MEMBER 로 내리지만, 아니어도 다시 승인되는 순간 운영진으로 돌아오면 안 된다
                role = MemberRole.MEMBER
                // 거절 기록을 남겨 두면 화면이 대기 상태 위에 "거절됨" 시각을 그릴 수 있다
                decidedAt = null
                decidedBy = null
            }
            // 대기 중엔 닉네임 오타를 고칠 수 있게 한다. 관리자가 아직 안 봤으니 바꿔도 속는 사람이 없다
            MemberStatus.PENDING -> existing.nickname = name
            // 가입 신청으로는 승인된 사람의 아무것도 안 바꾼다 — 닉네임·프로필을 고치는 길은 PUT /api/me/profile 이다
            MemberStatus.APPROVED -> return me(uid)
        }
        if (profile != null) saveProfile(uid, profile)
        return me(uid)
    }

    /**
     * 닉네임과 프로필을 **통째로** 바꾼다 (§4.11). 대기 중·승인된 멤버만.
     *
     * 1단계의 "승인된 뒤에는 닉네임을 안 바꾼다" 를 **뒤집은 자리다**(사용자 결정 · 기획 6단계).
     * 그때 이유는 "운영진이 그 닉네임을 보고 승인했다" 였는데, 승인 뒤의 개명은 멤버 목록에 그대로 보인다.
     *
     * 멤버 행을 잠그고 쓴다 — 처음 저장을 두 탭에서 동시에 하면 둘 다 "없음" 을 보고 INSERT 해서 하나가 500 이 된다
     */
    fun updateProfile(uid: String, nickname: String, profile: ProfileInput?): MeDto {
        val name = cleanNickname(nickname)
        val clean = ProfileRules.clean(profile ?: throw ClubError.badProfile(), todayKst())
        return tx.execute {
            val m = members.findForUpdateByUserId(uid)
            when (m?.status) {
                // 내보낸 사람도 같다 — 다시 가입 신청하면 대기가 되어 고칠 수 있다
                null, MemberStatus.REJECTED, MemberStatus.REMOVED -> throw ClubError.notJoined()
                MemberStatus.PENDING, MemberStatus.APPROVED -> m.nickname = name
            }
            saveProfile(uid, clean)
            me(uid)
        }!!
    }

    /** 멤버 행을 잠근 안에서만 부른다 — 그래야 "없으면 INSERT" 가 두 번 돌지 않는다 */
    private fun saveProfile(uid: String, clean: CleanProfile) {
        val now = Instant.now()
        val row = profiles.findByUserId(uid)
        if (row == null) profiles.saveAndFlush(clean.newRow(uid, now)) else clean.applyTo(row, now)
    }

    /** [status] 는 `PENDING` · `APPROVED` · `ALL` (그 밖의 상태 이름도 받는다). 가입 순 */
    @Transactional(readOnly = true)
    fun list(adminUid: String, status: String): List<MemberDto> {
        requireAdmin(adminUid, lock = false)
        val rows = if (status == ALL) {
            members.findAllByOrderByCreatedAt()
        } else {
            val wanted = MemberStatus.entries.firstOrNull { it.name == status }
                // 계약 밖의 값이다. 화면이 틀린 것이라 코드 대신 평범한 400 으로 답한다
                ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "status 는 PENDING·APPROVED·ALL 중 하나")
            members.findByStatusOrderByCreatedAt(wanted)
        }
        // 기기 유무를 사람마다 물으면 30명에 쿼리 30번이다. 한 번에 묻는다
        val withDevice = if (rows.isEmpty()) emptySet() else devices.findUserIdsWithDevice(rows.map { it.userId }).toSet()
        val profileOf = if (rows.isEmpty()) emptyMap() else profiles.findByUserIdIn(rows.map { it.userId }).associateBy { it.userId }
        return rows.map { it.toDto(it.userId in withDevice, profileOf[it.userId]?.toDto()) }
    }

    @Transactional
    fun approve(adminUid: String, uid: String): MemberDto = decide(adminUid, uid) { m, _ ->
        when (m.status) {
            // 거절했다가 마음을 바꾼 경우도 받는다
            MemberStatus.PENDING, MemberStatus.REJECTED -> {
                m.status = MemberStatus.APPROVED
                // decidedAt 은 역할 바꾸기·내보내기에도 새로 찍힌다. 승인일은 여기서만 (D35)
                m.approvedAt = Instant.now()
            }
            MemberStatus.APPROVED -> return@decide false
            MemberStatus.REMOVED -> throw ClubError.badState()
        }
        true
    }

    @Transactional
    fun reject(adminUid: String, uid: String): MemberDto = decide(adminUid, uid) { m, _ ->
        when (m.status) {
            MemberStatus.PENDING -> m.status = MemberStatus.REJECTED
            MemberStatus.REJECTED -> return@decide false
            // 승인된 사람을 빼려면 remove 다. 거절은 "가입 신청" 에 대한 답이다
            MemberStatus.APPROVED, MemberStatus.REMOVED -> throw ClubError.badState()
        }
        true
    }

    @Transactional
    fun remove(adminUid: String, uid: String): MemberDto = decide(adminUid, uid) { m, admins ->
        if (m.status == MemberStatus.REMOVED) return@decide false
        if (m.isActiveAdmin() && admins.size <= 1) throw ClubError.lastAdmin()
        m.status = MemberStatus.REMOVED
        // ADMIN 으로 남기면, 다시 가입 신청해서 승인될 때 관리자로 돌아온다
        m.role = MemberRole.MEMBER
        true
    }

    @Transactional
    fun setRole(adminUid: String, uid: String, role: MemberRole): MemberDto = decide(adminUid, uid) { m, admins ->
        if (m.status != MemberStatus.APPROVED) throw ClubError.badState()
        if (m.role == role) return@decide false
        if (role == MemberRole.MEMBER && admins.size <= 1) throw ClubError.lastAdmin()
        m.role = role
        true
    }

    /**
     * 관리 작업의 뼈대 — 잠그고, 관리자인지 보고, 대상을 찾고, [change] 가 바꿨으면 결정 기록을 남긴다.
     * [change] 는 대상과 **잠근 관리자 목록**을 받는다. `false` 면 이미 그 상태다 — **그대로 200** (두 번 눌러도 같다, §4.6).
     *
     * 관리자 목록을 필드에 두지 않고 넘기는 이유: 이 서비스는 빈 하나를 모든 요청이 같이 쓴다.
     * 필드에 두면 동시에 온 다른 관리 요청이 그 값을 덮어쓴다.
     */
    private fun decide(adminUid: String, uid: String, change: (ClubMember, List<ClubMember>) -> Boolean): MemberDto {
        val admins = requireAdmin(adminUid, lock = true)
        val target = members.findForUpdateByUserId(uid) ?: throw ClubError.noSuchMember()
        if (change(target, admins)) {
            target.decidedAt = Instant.now()
            target.decidedBy = adminUid
            log.info("동호회 멤버 {} → {}/{} (관리자 {})", uid, target.status, target.role, adminUid)
        }
        return target.toDto(devices.findUserIdsWithDevice(listOf(uid)).isNotEmpty(), profiles.findByUserId(uid)?.toDto())
    }

    private fun requireAdmin(adminUid: String, lock: Boolean): List<ClubMember> {
        val admins = if (lock) {
            members.findForUpdateByRoleAndStatus(MemberRole.ADMIN, MemberStatus.APPROVED)
        } else {
            members.findByUserId(adminUid)?.takeIf { it.isActiveAdmin() }?.let(::listOf).orEmpty()
        }
        if (admins.none { it.userId == adminUid }) throw ClubError.notAdmin()
        return admins
    }

    private fun ClubMember.isActiveAdmin() = role == MemberRole.ADMIN && status == MemberStatus.APPROVED

    private fun ClubMember.toDto(hasDevice: Boolean, profile: ProfileDto?) = MemberDto(
        uid = userId,
        nickname = nickname,
        role = role.name,
        status = status.name,
        createdAt = createdAt,
        decidedAt = decidedAt,
        hasDevice = hasDevice,
        profile = profile,
    )

    companion object {
        const val NONE = "NONE"
        const val ALL = "ALL"
        const val NICKNAME_MAX = 20

        /**
         * 앞뒤 공백을 지우고 **글자(코드 포인트)** 로 1~20자.
         * `String.length` 는 UTF-16 단위라 이모지를 둘로 센다 — DB 의 `VARCHAR(20)` 은 하나로 센다.
         * 둘이 다르게 세면 "서버는 통과시켰는데 DB 가 거절" 하는 500 이 생긴다
         */
        fun cleanNickname(raw: String): String {
            val name = raw.trim()
            val chars = name.codePointCount(0, name.length)
            if (chars < 1 || chars > NICKNAME_MAX) throw ClubError.badNickname()
            return name
        }
    }
}
