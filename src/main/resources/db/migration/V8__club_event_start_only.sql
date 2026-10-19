-- 시작 시각만 있는 일정을 허용한다 (아키텍처 D30 정정 · Floduler `작업명세서.md` §4.7).
-- "19:00 모임" 처럼 끝을 정하지 않는 모임이 흔하다. 끝만 있는 일정은 여전히 막는다 — 언제 시작하는지 모르는 일정은 목록에 놓을 자리가 없다.
--
-- V7 을 고치지 않고 새 파일로 바꾸는 이유: 운영 DB 에 V7 이 이미 돌았다. Flyway 는 돈 파일의 체크섬을 기억해서,
-- 고치면 다음 기동이 "V7 이 바뀌었다" 며 서버가 안 뜬다.
ALTER TABLE club_event DROP CONSTRAINT ck_club_event_time;
ALTER TABLE club_event ADD CONSTRAINT ck_club_event_time CHECK (
    (start_min IS NULL AND end_min IS NULL)
    OR (start_min IS NOT NULL AND start_min BETWEEN 0 AND 2879
        AND (end_min IS NULL OR (end_min > start_min AND end_min <= 2879)))
);
