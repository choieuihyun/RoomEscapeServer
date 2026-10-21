-- 프로필 거주지역 (사용자 요청 2026-10-21 · 아키텍처 D35 · 계약 Floduler `작업명세서.md` §4.11).
-- **비워 둘 수 있는 열**이다 — 기존 행은 null 로 남고, 옛 앱이 보내는 프로필에도 이 칸이 없다.
-- 필수는 화면이 강제한다. 서버가 NOT NULL 로 걸면 옛 앱의 가입·고치기가 막힌다 (서버가 더 받는 쪽)
-- 서비스가 코드 포인트로 1~20자를 센다 (이모지 1개 = 1자, VARCHAR 와 같은 셈)
ALTER TABLE member_profile ADD COLUMN residence VARCHAR(20);
