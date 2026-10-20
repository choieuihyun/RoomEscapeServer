package com.my_dream.server.club

import com.my_dream.server.domain.ClubMemberRepository
import com.my_dream.server.domain.PayInfo
import com.my_dream.server.domain.PayInfoRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

data class PayInfoInput(val bankText: String? = null, val kakaopayUrl: String? = null)

/**
 * 내 송금 정보 (§4.13 · D37) — 계좌 글과 카카오페이 송금 링크. 정산에서 **받을 사람 것만** 남에게 보인다.
 * 🔒 계좌 글은 개인정보다 — **로그에 남기지 않는다.** DB 와 백업에는 남는다(배포.md "DB 백업")
 */
@Service
class PayInfoService(
    private val members: ClubMemberRepository,
    private val infos: PayInfoRepository,
) {

    @Transactional(readOnly = true)
    fun mine(uid: String): PayInfoDto {
        members.requireApproved(uid)
        return infos.findById(uid).orElse(null)?.let { PayInfoDto(it.bankText, it.kakaopayUrl) } ?: PayInfoDto(null, null)
    }

    /** 둘 다 통째로 바꾼다. 비우면(빈 글자) 없음 — "빈 문자열" 과 "없음" 을 두 가지로 두지 않는다 */
    @Transactional
    fun save(uid: String, input: PayInfoInput): PayInfoDto {
        members.requireApproved(uid)
        val bank = input.bankText?.trim()?.ifEmpty { null }
        if (bank != null && bank.codePointCount(0, bank.length) > BANK_MAX) throw ClubError.badPayInfo()
        val url = input.kakaopayUrl?.trim()?.ifEmpty { null }
        // 아무 주소나 받으면 다른 멤버 화면의 "카카오페이로 보내기" 가 엉뚱한 곳(javascript: 포함)으로 간다.
        // 공백도 막는다 — 링크 중간의 공백은 복사 실수다
        if (url != null && (!url.startsWith(KAKAOPAY_PREFIX) || url.length > URL_MAX || url.any(Char::isWhitespace))) throw ClubError.badPayInfo()

        val row = infos.findById(uid).orElse(null)
        if (row == null) {
            infos.save(PayInfo(uid, bank, url, Instant.now()))
        } else {
            row.bankText = bank
            row.kakaopayUrl = url
            row.updatedAt = Instant.now()
        }
        return PayInfoDto(bank, url)
    }

    companion object {
        const val BANK_MAX = 60
        const val URL_MAX = 200
        const val KAKAOPAY_PREFIX = "https://qr.kakaopay.com/"
    }
}
