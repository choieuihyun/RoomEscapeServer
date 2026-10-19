package com.my_dream.server.club

import com.my_dream.server.domain.ClubEvent
import com.my_dream.server.domain.ClubEventAvailability
import com.my_dream.server.domain.ClubEventAvailabilityRepository
import com.my_dream.server.domain.ClubEventCandidate
import com.my_dream.server.domain.ClubEventCandidateRepository
import com.my_dream.server.domain.ClubEventParticipant
import com.my_dream.server.domain.ClubEventParticipantRepository
import com.my_dream.server.domain.ClubEventRepository
import com.my_dream.server.domain.ClubEventSegment
import com.my_dream.server.domain.ClubEventSegmentRepository
import com.my_dream.server.domain.ClubMember
import com.my_dream.server.domain.ClubMemberRepository
import com.my_dream.server.domain.MemberRole
import com.my_dream.server.domain.MemberStatus
import com.my_dream.server.domain.PlayedThemeRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Limit
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.text.Collator
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.Locale

// --- 계약 (Floduler `작업명세서.md` §4.7 · §4.8). 모양을 바꾸려면 그 문서부터 ---

/**
 * 만들기·수정 본문. **전부 null 을 받는다** — 빠진 값을 Jackson 이 아니라 여기서 걸러야
 * 스프링 기본 400 대신 계약의 `BAD_EVENT` 가 나간다. [version] 은 수정 때만 쓴다.
 */
data class EventInput(
    val title: String? = null,
    val date: String? = null,
    val startMin: Int? = null,
    val endMin: Int? = null,
    val location: String? = null,
    val capacity: Int? = null,
    val booked: Boolean? = null,
    val memo: String? = null,
    val segments: List<SegmentInput>? = null,
    val version: Long? = null,
    /**
     * 후보 날짜 (D32). **null 이면 수정 때 그대로 둔다** — 이미 배포된 2단계 화면은 이 필드를 모른다. `[]` 를 보내야 지운다.
     *
     * 항목은 `"YYYY-MM-DD"` 또는 **응답의 모양 그대로** `{date, responses}` 둘 다 받는다(`date` 만 본다). 화면은 받은
     * EventDto 를 고쳐 통째로 돌려보낸다(§4.7) — 문자열만 받으면 그 화면이 미정 일정을 고칠 때마다 `BAD_EVENT` 다
     * (2026-10-19 `ClubEventApiTest` 가 잡았다)
     */
    val candidates: List<Any?>? = null,
)

/** 후보 날짜 하나에 대한 내 응답 (§4.8). 시각이 둘 다 null 이면 종일 됨(또는 안 됨) */
data class AvailabilityInput(
    val date: String? = null,
    val available: Boolean? = null,
    val startMin: Int? = null,
    val endMin: Int? = null,
)

data class SegmentInput(
    val themeName: String? = null,
    val branchName: String? = null,
    val themeKey: String? = null,
    val startMin: Int? = null,
    val endMin: Int? = null,
)

data class SegmentDto(
    val themeName: String,
    val branchName: String?,
    val themeKey: String?,
    val startMin: Int,
    val endMin: Int?,
    /** 그 일정 참여자 중 이 테마를 해 본 사람 (§4.9 · D33). 읽을 때마다 계산한다 — 입력 검사도 이 클래스를 쓰므로 기본값이 있다 */
    val playedBy: List<String> = emptyList(),
)

data class PersonDto(val uid: String, val nickname: String)

data class ResponseDto(val uid: String, val available: Boolean, val startMin: Int?, val endMin: Int?)

/** 후보 날짜 하나와 그 날짜의 응답들. 날짜를 정한 뒤에도 그대로 나간다 (다시 미정으로 돌릴 수 있게) */
data class CandidateDto(val date: LocalDate, val responses: List<ResponseDto>)

data class ParticipantDto(val uid: String, val nickname: String, val addedBy: String?, val joinedAt: Instant)

/** [joined]·[mine]·[canManage] 는 **요청한 사람 기준**이다 — 같은 일정도 보는 사람마다 다르다 */
data class EventDto(
    val id: Long,
    val title: String,
    /** null 이면 날짜 미정 — [candidates] 가 1개 이상 있다 (D32) */
    val date: LocalDate?,
    val startMin: Int?,
    val endMin: Int?,
    val location: String?,
    val capacity: Int?,
    val booked: Boolean,
    val memo: String?,
    val segments: List<SegmentDto>,
    /** 날짜 순 */
    val candidates: List<CandidateDto>,
    val version: Long,
    val createdBy: PersonDto,
    val createdAt: Instant,
    val updatedAt: Instant,
    val participants: List<ParticipantDto>,
    val count: Int,
    val joined: Boolean,
    val mine: Boolean,
    val canManage: Boolean,
)

data class DeletedDto(val id: Long, val deleted: Boolean)

/** `GET /api/me/events` (§4.9). [total] 은 커서와 상관없이 내가 참여한 지난 일정 전체 수 */
data class MyEventsDto(val events: List<EventDto>, val total: Long)

data class ClubMemberDto(val uid: String, val nickname: String)

/**
 * 동호회 일정 (아키텍처 D30 · D31).
 *
 * **모든 메서드가 [requireMember] 로 시작한다** — 승인 멤버가 아니면 `NOT_CLUB_MEMBER`. 메서드마다 따로 쓰면
 * 하나를 빠뜨렸을 때 그 경로만 조용히 열린다. 이 판정과 "관리할 수 있나"([canManage]) 는 이 파일에 하나씩만 있다.
 *
 * 잠금은 **일정 행 하나뿐**이다(`findForUpdate`). 멤버 행은 읽기만 한다 — D28 의 멤버십 잠금(관리자 전부 → 대상)과
 * 순서가 엇갈려 교착이 생기지 않게.
 */
@Service
class ClubEventService(
    private val events: ClubEventRepository,
    private val segments: ClubEventSegmentRepository,
    private val participants: ClubEventParticipantRepository,
    private val candidates: ClubEventCandidateRepository,
    private val answers: ClubEventAvailabilityRepository,
    private val played: PlayedThemeRepository,
    private val members: ClubMemberRepository,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    // --- 읽기 ---

    /** [from]·[to] 는 양끝 포함, **최대 62일치**. 문자열로 받는 이유: 형식이 틀려도 스프링 기본 400 이 아니라 `BAD_RANGE` 여야 한다 */
    @Transactional(readOnly = true)
    fun list(uid: String, from: String?, to: String?): List<EventDto> {
        val viewer = requireMember(uid)
        val start = parseDate(from) ?: throw ClubError.badRange()
        val end = parseDate(to) ?: throw ClubError.badRange()
        if (end < start || ChronoUnit.DAYS.between(start, end) > MAX_RANGE_DAYS) throw ClubError.badRange()
        // 순서: (날짜, 미정이면 범위 안 가장 이른 후보) → 하루 종일 먼저 → 시작 → id (§4.8).
        // DB 가 아니라 여기서 정한다 — 미정 일정의 기준 날짜가 후보 표에 있다. 그리고 NULL 순서가 Postgres·H2 에서 반대라
        // (Postgres 는 오름차순에서 NULL 이 뒤) DB 에 맡기면 테스트와 운영이 다르게 나온다 (D31 아래 · D32)
        val dtos = toDtos(events.findLiveBetween(start, end), viewer)
        fun day(e: EventDto) = e.date ?: e.candidates.map { it.date }.filter { it in start..end }.minOrNull()
        return dtos.sortedWith(
            compareBy<EventDto> { day(it) }.thenBy(nullsFirst<Int>()) { it.startMin }.thenBy { it.id },
        )
    }

    @Transactional(readOnly = true)
    fun get(uid: String, id: Long): EventDto {
        val viewer = requireMember(uid)
        val event = events.findById(id).orElse(null)?.takeIf { it.deletedAt == null } ?: throw ClubError.noSuchEvent()
        return toDto(event, viewer)
    }

    /**
     * 내가 참여한 지난 일정 (§4.9). 커서는 (date, id) — 화면은 받은 마지막 일정의 date·id 를 [before]·[beforeId] 로 보낸다.
     * [before] 만 오면 그날 이전, 둘 다 없으면 오늘(KST) 이전. 날짜 미정은 빠진다. 문자열로 받는 이유는 [list] 와 같다
     */
    @Transactional(readOnly = true)
    fun myPast(uid: String, before: String?, beforeId: String?, limit: String?, today: LocalDate = todayKst()): MyEventsDto {
        val viewer = requireMember(uid)
        val until = if (before == null) today else parseDate(before) ?: throw ClubError.badRange()
        // id 만 오면 어느 날의 id 인지 모른다
        val idCursor = when {
            beforeId == null -> 0L
            before == null -> throw ClubError.badRange()
            else -> beforeId.toLongOrNull()?.takeIf { it > 0 } ?: throw ClubError.badRange()
        }
        val n = if (limit == null) 20 else limit.toIntOrNull()?.takeIf { it in 1..50 } ?: throw ClubError.badRange()
        return MyEventsDto(
            events = toDtos(events.findPastJoined(uid, until, idCursor, Limit.of(n)), viewer),
            total = events.countPastJoined(uid, today),
        )
    }

    /** 대신 넣을 사람 고르기용. 승인 멤버만, **한국어 가나다순** — DB 정렬 규칙(collation)에 맡기면 서버마다 다르다 */
    @Transactional(readOnly = true)
    fun members(uid: String): List<ClubMemberDto> {
        requireMember(uid)
        val korean = Collator.getInstance(Locale.KOREAN)
        return members.findByStatusOrderByCreatedAt(MemberStatus.APPROVED)
            .map { ClubMemberDto(it.userId, it.nickname) }
            .sortedWith { a, b -> korean.compare(a.nickname, b.nickname) }
    }

    // --- 만들기 · 수정 · 삭제 ---

    @Transactional
    fun create(uid: String, input: EventInput): EventDto {
        val viewer = requireMember(uid)
        val v = validate(input)
        if (v.date == null && v.candidates.isNullOrEmpty()) throw ClubError.badEvent()
        val now = Instant.now()
        val event = events.save(
            ClubEvent(v.title, v.date, v.startMin, v.endMin, v.location, v.capacity, v.booked, v.memo, uid, now, now),
        )
        saveSegments(event, v.segments)
        v.candidates?.forEach { candidates.save(ClubEventCandidate(event, it)) }
        // 만든 사람은 자동 참여다 (기획 "2단계"). 정원은 2 이상이라 늘 자리가 있다
        participants.save(ClubEventParticipant(event, uid, null, now))
        events.flush()
        return toDto(event, viewer)
    }

    /**
     * 확인 순서가 정해져 있다 (D31): 입력 검사(잠금 없이) → 잠금 → 없음·삭제됨 → 관리할 수 있나 → version → 정원.
     * 입력이 틀린 요청 때문에 행을 잠그지 않고, 권한 없는 사람에게 "다른 사람이 고쳤어요" 를 먼저 보여 주지 않는다.
     */
    @Transactional
    fun update(uid: String, id: Long, input: EventInput): EventDto {
        val viewer = requireMember(uid)
        val v = validate(input)
        val expected = input.version ?: throw ClubError.badEvent()
        val event = lockLive(id)
        if (!canManage(event, viewer)) throw ClubError.notEventOwner()
        // @Version 만으로는 못 잡는다 — 요청마다 엔티티를 새로 읽으니, 화면이 10분 들고 있던 옛 내용도 JPA 눈엔 최신이다
        if (event.version != expected) throw ClubError.eventConflict(toDto(event, viewer))
        if (v.capacity != null && participants.countByEvent(event) > v.capacity) throw ClubError.capacityTooSmall()
        // 후보를 안 보냈으면 지금 있는 후보로 센다 — 2단계 화면이 미정 일정을 고쳐도 BAD_EVENT 가 안 나게 (★ §4.8)
        if (v.date == null && (v.candidates?.size?.toLong() ?: candidates.countByEvent(event)) == 0L) throw ClubError.badEvent()

        event.title = v.title
        event.date = v.date
        event.startMin = v.startMin
        event.endMin = v.endMin
        event.location = v.location
        event.capacity = v.capacity
        event.booked = v.booked
        event.memo = v.memo
        // 같은 내용으로 고쳐도 version 이 오르게 하는 것이 이 줄이다 — 엔티티가 늘 "바뀐" 상태가 된다 (계약 "version +1")
        event.updatedAt = Instant.now()
        segments.deleteByEvent(event)
        saveSegments(event, v.segments)
        v.candidates?.let { replaceCandidates(event, it) }
        // **응답 전에 내보낸다.** @Version 은 flush 때 오른다 — 안 하면 옛 번호가 나가서 화면의 다음 수정이 자기 자신과 충돌한다
        events.flush()
        return toDto(event, viewer)
    }

    @Transactional
    fun delete(uid: String, id: Long): DeletedDto {
        val viewer = requireMember(uid)
        val event = lockLive(id)
        if (!canManage(event, viewer)) throw ClubError.notEventOwner()
        event.deletedAt = Instant.now()
        event.deletedBy = uid
        log.info("동호회 일정 {} 삭제 표시 (by {})", id, uid)
        return DeletedDto(id, true)
    }

    // --- 참여 (일정 행을 잠그지만 바꾸지는 않는다 → version 그대로, D31) ---

    @Transactional
    fun join(uid: String, id: Long): EventDto {
        val viewer = requireMember(uid)
        val event = lockLive(id)
        seat(event, uid, addedBy = null)
        return toDto(event, viewer)
    }

    @Transactional
    fun leave(uid: String, id: Long): EventDto {
        val viewer = requireMember(uid)
        val event = lockLive(id)
        // 만든 사람도 취소할 수 있다(사용자 결정). 취소해도 mine·관리 권한은 그대로다
        unseat(event, uid)
        return toDto(event, viewer)
    }

    @Transactional
    fun addParticipant(uid: String, id: Long, target: String): EventDto {
        val viewer = requireMember(uid)
        val event = lockLive(id)
        if (!canManage(event, viewer)) throw ClubError.notEventOwner()
        // 승인 멤버만 넣는다. 가입 신청 중이거나 내보낸 사람을 대신 넣으면 그 사람은 일정을 볼 수도 없다
        if (members.findByUserId(target)?.status != MemberStatus.APPROVED) throw ClubError.noSuchMember()
        seat(event, target, addedBy = uid)
        return toDto(event, viewer)
    }

    @Transactional
    fun removeParticipant(uid: String, id: Long, target: String): EventDto {
        val viewer = requireMember(uid)
        val event = lockLive(id)
        if (!canManage(event, viewer)) throw ClubError.notEventOwner()
        unseat(event, target)
        return toDto(event, viewer)
    }

    /** 같은 값이면 그대로(멱등), 바뀌면 version 이 오른다 — 예약 여부는 일정의 "내용" 이다 */
    @Transactional
    fun setBooking(uid: String, id: Long, booked: Boolean): EventDto {
        val viewer = requireMember(uid)
        val event = lockLive(id)
        val isParticipant = participants.findByEventAndUserId(event, uid) != null
        if (!canManage(event, viewer) && !isParticipant) throw ClubError.notParticipant()
        if (event.booked != booked) {
            event.booked = booked
            event.updatedAt = Instant.now()
            events.flush()
        }
        return toDto(event, viewer)
    }

    // --- 시간 협의 응답 (§4.8 · D32) — 참여와 같이 일정 행을 잠그지만 바꾸지는 않는다 → version 그대로 ---

    /**
     * 같은 날짜면 덮어쓴다. 날짜를 정한 뒤에도 받는다(다시 미정으로 돌릴 수 있게).
     * 잠그는 이유: 후보 바꾸기([update])도 같은 행을 잠그므로, 방금 지운 후보에 응답이 붙지 않는다
     */
    @Transactional
    fun answer(uid: String, id: Long, input: AvailabilityInput): EventDto {
        val viewer = requireMember(uid)
        val date = parseDate(input.date) ?: throw ClubError.badAvailability()
        val available = input.available ?: throw ClubError.badAvailability()
        val (start, end) = input.startMin to input.endMin
        val timed = start != null || end != null
        if (timed && !(available && start != null && end != null && start in 0..MAX_MIN && end in (start + 1)..MAX_MIN)) {
            throw ClubError.badAvailability()
        }
        val event = lockLive(id)
        if (participants.findByEventAndUserId(event, uid) == null) throw ClubError.notParticipantAnswer()
        val candidate = candidates.findByEventAndDate(event, date) ?: throw ClubError.notACandidate()

        val now = Instant.now()
        val row = answers.findByCandidateAndUserId(candidate, uid)
        if (row == null) {
            answers.saveAndFlush(ClubEventAvailability(candidate, uid, available, start, end, now))
        } else {
            row.available = available
            row.startMin = start
            row.endMin = end
            row.updatedAt = now
            answers.flush()
        }
        return toDto(event, viewer)
    }

    /** "응답 안 함". 후보가 아니거나 응답이 없으면 지울 게 없으니 그대로 200 */
    @Transactional
    fun clearAnswer(uid: String, id: Long, rawDate: String): EventDto {
        val viewer = requireMember(uid)
        val date = parseDate(rawDate) ?: throw ClubError.badAvailability()
        val event = lockLive(id)
        if (participants.findByEventAndUserId(event, uid) == null) throw ClubError.notParticipantAnswer()
        candidates.findByEventAndDate(event, date)
            ?.let { answers.findByCandidateAndUserId(it, uid) }
            ?.let { answers.delete(it); answers.flush() }
        return toDto(event, viewer)
    }

    // --- 판정 (한 군데씩만) ---

    /** 승인된 멤버인가. 모든 일정 API 의 첫 줄 */
    private fun requireMember(uid: String): ClubMember = members.requireApproved(uid)

    /** 만든 사람이거나 운영진(D28 의 ADMIN·APPROVED). 응답의 `canManage` 도 이 판정이다 */
    private fun canManage(event: ClubEvent, viewer: ClubMember) =
        event.createdBy == viewer.userId || (viewer.role == MemberRole.ADMIN && viewer.status == MemberStatus.APPROVED)

    private fun lockLive(id: Long): ClubEvent =
        events.findForUpdate(id)?.takeIf { it.deletedAt == null } ?: throw ClubError.noSuchEvent()

    /**
     * 자리 하나. **"이미 있나" 를 정원보다 먼저** 본다 — 꽉 찬 일정에 이미 들어가 있는 사람이
     * 다시 눌렀을 때 "자리가 다 찼어요" 를 받으면 안 된다. 일정 행이 잠겨 있으니 세는 동안 아무도 끼어들지 못한다
     */
    private fun seat(event: ClubEvent, uid: String, addedBy: String?) {
        if (participants.findByEventAndUserId(event, uid) != null) return
        val cap = event.capacity
        if (cap != null && participants.countByEvent(event) >= cap) throw ClubError.eventFull()
        participants.saveAndFlush(ClubEventParticipant(event, uid, addedBy, Instant.now()))
    }

    /** 참여 취소·빼기. **응답도 같이 지운다** — 안 갈 사람의 "됨" 이 남으면 "다 같이 되는 날" 이 틀린다 (D32) */
    private fun unseat(event: ClubEvent, uid: String) {
        participants.findByEventAndUserId(event, uid)?.let { participants.delete(it) }
        participants.flush()
        answers.deleteByEventAndUserId(event, uid)
    }

    /**
     * 후보를 **차이만** 바꾼다 (D32). 통째로 지웠다 넣으면 남은 날짜의 응답까지 사라진다.
     * 빠진 날짜는 응답부터 지우고(후보를 가리키므로) 후보를 지운다. 새 날짜는 넣는다
     */
    private fun replaceCandidates(event: ClubEvent, dates: List<LocalDate>) {
        val existing = candidates.findByEvent(event)
        val gone = existing.filter { it.date !in dates }
        if (gone.isNotEmpty()) {
            answers.deleteByCandidates(gone)
            candidates.deleteAllIn(gone)
        }
        val kept = existing.map { it.date }.toSet()
        dates.filter { it !in kept }.forEach { candidates.save(ClubEventCandidate(event, it)) }
    }

    private fun saveSegments(event: ClubEvent, list: List<SegmentDto>) {
        segments.saveAll(list.mapIndexed { i, s -> ClubEventSegment(event, i, s.themeName, s.branchName, s.themeKey, s.startMin, s.endMin) })
    }

    // --- 응답 만들기 — 일정 n 개에 쿼리 6개 (구간 · 참여자 · 후보 · 응답 · 해 본 테마 · 닉네임), N+1 을 만들지 않는다 ---

    private fun toDto(event: ClubEvent, viewer: ClubMember) = toDtos(listOf(event), viewer).single()

    private fun toDtos(list: List<ClubEvent>, viewer: ClubMember): List<EventDto> {
        if (list.isEmpty()) return emptyList()
        val ids = list.map { requireNotNull(it.id) }
        val segsByEvent = segments.findByEventIds(ids).groupBy { requireNotNull(it.event.id) }
        val peopleByEvent = participants.findByEventIds(ids).groupBy { requireNotNull(it.event.id) }
        val candsByEvent = candidates.findByEventIds(ids).groupBy { requireNotNull(it.event.id) }
        val answersByCand = answers.findByEventIds(ids).groupBy { requireNotNull(it.candidate.id) }
        val playedByUser = played.findByUserIdIn(peopleByEvent.values.flatten().map { it.userId }.toSet()).groupBy { it.userId }
        val uids = list.map { it.createdBy } + peopleByEvent.values.flatten().map { it.userId }
        // 내보낸 멤버도 행이 남아 있으니 닉네임이 나온다. 행이 아예 없는 경우는 없지만, 있어도 화면이 깨지지 않게 빈 문자열
        val nick = members.findByUserIdIn(uids.toSet()).associate { it.userId to it.nickname }

        return list.map { e ->
            val id = requireNotNull(e.id)
            val people = peopleByEvent[id].orEmpty()
            EventDto(
                id = id,
                title = e.title,
                date = e.date,
                startMin = e.startMin,
                endMin = e.endMin,
                location = e.location,
                capacity = e.capacity,
                booked = e.booked,
                memo = e.memo,
                segments = segsByEvent[id].orEmpty().map { seg ->
                    val nameKey = PlayedMatch.nameKey(seg.themeName)
                    val who = people.map { it.userId }.filter { u ->
                        playedByUser[u].orEmpty().any { PlayedMatch.same(it.themeKey, it.nameKey, seg.themeKey, nameKey) }
                    }
                    SegmentDto(seg.themeName, seg.branchName, seg.themeKey, seg.startMin, seg.endMin, who)
                },
                candidates = candsByEvent[id].orEmpty().map { c ->
                    CandidateDto(c.date, answersByCand[c.id].orEmpty().map { ResponseDto(it.userId, it.available, it.startMin, it.endMin) })
                },
                version = e.version,
                createdBy = PersonDto(e.createdBy, nick[e.createdBy].orEmpty()),
                createdAt = e.createdAt,
                updatedAt = e.updatedAt,
                participants = people.map { ParticipantDto(it.userId, nick[it.userId].orEmpty(), it.addedBy, it.joinedAt) },
                count = people.size,
                joined = people.any { it.userId == viewer.userId },
                mine = e.createdBy == viewer.userId,
                canManage = canManage(e, viewer),
            )
        }
    }

    // --- 입력 검사 (§4.7) — 틀리면 전부 BAD_EVENT 하나다. 화면이 먼저 검사하므로 어느 칸인지까지는 안 알린다 ---

    private data class Valid(
        val title: String, val date: LocalDate?, val startMin: Int?, val endMin: Int?, val location: String?,
        val capacity: Int?, val booked: Boolean, val memo: String?, val segments: List<SegmentDto>,
        /** null = 안 보냄(수정 때 그대로) */
        val candidates: List<LocalDate>?,
    )

    private fun validate(i: EventInput): Valid {
        val title = text(i.title ?: "", max = 60, allowEmpty = true)
        // null 은 날짜 미정(D32). 형식이 틀린 것과는 다르다 — 그건 BAD_EVENT
        val date = i.date?.let { parseDate(it) ?: throw ClubError.badEvent() }
        val cands = i.candidates?.map { c ->
            val raw = when (c) {
                is String -> c
                is Map<*, *> -> c["date"] as? String
                else -> null
            }
            parseDate(raw) ?: throw ClubError.badEvent()
        }
        if (cands != null && (cands.size > MAX_CANDIDATES || cands.toSet().size != cands.size)) throw ClubError.badEvent()
        // 만들 때는 "안 보냄" 이 곧 0개다. 수정 때의 "그대로" 는 지금 후보 수를 알아야 해서 update 가 본다
        if (date == null && cands != null && cands.isEmpty()) throw ClubError.badEvent()
        // 시작만 있어도 된다("19:00 모임"). 끝만 있는 것은 안 된다 — 시작을 모르면 목록에 놓을 자리가 없다 (V8)
        if (i.startMin == null && i.endMin != null) throw ClubError.badEvent()
        if (i.startMin != null && i.startMin !in 0..MAX_MIN) throw ClubError.badEvent()
        if (i.startMin != null && i.endMin != null && i.endMin !in (i.startMin + 1)..MAX_MIN) throw ClubError.badEvent()
        if (i.capacity != null && i.capacity !in 2..8) throw ClubError.badEvent()
        val booked = i.booked ?: throw ClubError.badEvent()
        val segs = i.segments.orEmpty()
        if (segs.size > 6) throw ClubError.badEvent()
        val cleanSegs = segs.map { s ->
            val start = s.startMin ?: throw ClubError.badEvent()
            if (start !in 0..MAX_MIN) throw ClubError.badEvent()
            if (s.endMin != null && s.endMin !in (start + 1)..MAX_MIN) throw ClubError.badEvent()
            SegmentDto(
                themeName = text(s.themeName ?: "", max = 40, allowEmpty = false),
                branchName = optional(s.branchName, max = 40),
                themeKey = optional(s.themeKey, max = 80),
                startMin = start,
                endMin = s.endMin,
            )
        }
        // 제목도 구간도 없으면 무엇을 하는 일정인지 아무도 모른다
        if (title.isEmpty() && cleanSegs.isEmpty()) throw ClubError.badEvent()
        return Valid(title, date, i.startMin, i.endMin, optional(i.location, 60), i.capacity, booked, optional(i.memo, 500), cleanSegs, cands?.sorted())
    }

    /**
     * 앞뒤 공백을 지우고 **코드 포인트**로 센다 (D28 닉네임과 같은 이유 — Postgres VARCHAR 가 그렇게 센다).
     * 가운데 줄바꿈은 그대로 둔다 — 메모가 여러 줄이다
     */
    private fun text(raw: String, max: Int, allowEmpty: Boolean): String {
        val s = raw.trim()
        val n = s.codePointCount(0, s.length)
        if (n > max || (!allowEmpty && n == 0)) throw ClubError.badEvent()
        return s
    }

    /** 비면 null 로 저장한다 — "빈 문자열" 과 "없음" 을 두 가지로 두지 않는다 */
    private fun optional(raw: String?, max: Int): String? = raw?.let { text(it, max, allowEmpty = true) }?.ifEmpty { null }

    private fun parseDate(raw: String?): LocalDate? =
        try {
            raw?.let(LocalDate::parse)
        } catch (e: DateTimeParseException) {
            null
        }

    companion object {
        /** 다음날 23:59 — 자정 넘는 회차까지 (V7 의 CHECK 와 같은 값) */
        const val MAX_MIN = 2879

        /** `to - from` 의 최대. 양끝 포함이라 62일치다 */
        const val MAX_RANGE_DAYS = 61L

        /** 후보 날짜 최대 개수 (§4.8) — 2주치 */
        const val MAX_CANDIDATES = 14
    }
}
