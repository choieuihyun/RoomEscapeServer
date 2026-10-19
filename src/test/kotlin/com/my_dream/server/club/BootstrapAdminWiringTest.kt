package com.my_dream.server.club

import com.my_dream.server.domain.ClubMemberRepository
import com.my_dream.server.domain.MemberRole
import com.my_dream.server.domain.MemberStatus
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import kotlin.test.assertEquals

/**
 * **배포 첫날의 기동 경로** — 값이 들어 있는 채로 스프링이 [BootstrapAdmin] 을 직접 돌린다.
 *
 * [BootstrapAdminTest] 는 `run()` 을 손으로 부르고 테스트 트랜잭션 안에서 돈다. 그러면 프록시를 안 거쳐서
 * `@Transactional` 이 실제로 걸리는지(잠금 쿼리 `FOR UPDATE` 는 트랜잭션이 없으면 실패한다)를 못 본다.
 * 운영에서 그게 빠지면 **사용자가 `CLUB_BOOTSTRAP_ADMIN_UID` 를 넣는 순간 서버가 안 뜬다.**
 * FCM 배선이 단위 테스트를 다 통과하고도 기동에서 막혔던 것(D16 정정)과 같은 구멍이다.
 */
@SpringBootTest(properties = ["club.bootstrap-admin-uid=boot-uid"])
class BootstrapAdminWiringTest @Autowired constructor(
    private val members: ClubMemberRepository,
) {

    @Test
    fun `값이 있으면 기동하면서 첫 관리자가 생긴다`() {
        val admin = members.findByUserId("boot-uid")!!
        assertEquals(MemberRole.ADMIN, admin.role)
        assertEquals(MemberStatus.APPROVED, admin.status)
    }
}
