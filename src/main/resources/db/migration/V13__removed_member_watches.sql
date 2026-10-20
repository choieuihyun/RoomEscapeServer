-- 내보낸 멤버의 빈자리 알림(감시)을 한 번 지운다 (2026-10-20 사용자 결정 · 아키텍처 D28 정정).
-- 이제부터는 내보내기가 그 자리에서 지운다. 이 파일은 그 전에 내보낸 사람의 남은 감시만 맡는다.
-- 알림 기록(notification_log)은 V3 의 ON DELETE CASCADE 로 같이 지워진다.
-- 배포 전 운영 실측: 내보낸 멤버 0명 — 지워질 행은 없을 것으로 본다
DELETE FROM watch
WHERE user_id IN (SELECT user_id FROM club_member WHERE status = 'REMOVED');
