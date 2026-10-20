package com.my_dream.server.club

import com.my_dream.server.domain.ClubMember
import com.my_dream.server.domain.ClubMemberRepository
import com.my_dream.server.domain.MemberRole
import com.my_dream.server.domain.MemberStatus
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** 내 송금 정보 (§4.13 · D37). 카카오페이 링크를 막는 이유: 다른 멤버 화면의 "보내기" 단추가 그 주소로 간다 */
@DataJpaTest
@Import(PayInfoService::class)
class PayInfoServiceTest @Autowired constructor(
    private val service: PayInfoService,
    private val members: ClubMemberRepository,
) {

    @BeforeEach
    fun setUp() {
        members.save(ClubMember("나", "나", MemberRole.MEMBER, MemberStatus.APPROVED, Instant.now()))
        members.save(ClubMember("대기", "대기", MemberRole.MEMBER, MemberStatus.PENDING, Instant.now()))
    }

    private fun code(block: () -> Unit) = assertFailsWith<ClubError> { block() }.code

    private fun message(block: () -> Unit) = assertFailsWith<ClubError> { block() }.message

    @Test
    fun `없으면 둘 다 null, 저장하면 통째로 바뀌고 빈 글자는 없음`() {
        assertEquals(PayInfoDto(null, null), service.mine("나"))
        service.save("나", PayInfoInput(" 카카오뱅크 3333-01-1234567 홍길동 ", "https://qr.kakaopay.com/Ej7abc"))
        assertEquals(PayInfoDto("카카오뱅크 3333-01-1234567 홍길동", "https://qr.kakaopay.com/Ej7abc"), service.mine("나"))
        service.save("나", PayInfoInput("", null))
        assertEquals(PayInfoDto(null, null), service.mine("나"))
    }

    @Test
    fun `카카오페이 링크는 qr 주소만, 계좌 글은 60자까지`() {
        for (bad in listOf("javascript:alert(1)", "http://qr.kakaopay.com/x", "https://qr.kakaopay.com.evil.kr/x", "https://qr.kakaopay.com/a b", "https://qr.kakaopay.com/" + "a".repeat(177))) {
            assertEquals("BAD_PAYINFO", code { service.save("나", PayInfoInput(null, bad)) }, bad)
        }
        assertEquals("BAD_PAYINFO", code { service.save("나", PayInfoInput("가".repeat(61), null)) })
        // 문구는 칸마다 — 화면이 그대로 띄우니 어느 칸을 고칠지 보여야 한다
        assertEquals("계좌 글은 60자까지 적을 수 있어요.", message { service.save("나", PayInfoInput("가".repeat(61), "https://qr.kakaopay.com/x")) })
        assertEquals("카카오페이 링크는 https://qr.kakaopay.com/ 으로 시작하는 주소만 넣을 수 있어요.", message { service.save("나", PayInfoInput("계좌", "http://x")) })
        service.save("나", PayInfoInput("🙂".repeat(60), "https://qr.kakaopay.com/" + "a".repeat(176)))
        assertEquals("NOT_CLUB_MEMBER", code { service.mine("대기") })
    }
}
