-- 멤버 프로필 · 랭킹 (아키텍처 D35). 계약은 Floduler `작업명세서.md` §4.11.

-- 한 사람 한 행. **행이 없으면 프로필을 아직 안 채운 것이다** — 기존 멤버는 전부 그렇다(옮길 데이터 없음).
-- club_member 에 열을 붙이지 않은 이유는 D35. 멤버 행과 FK 로 잇지 않는 것은 played_theme 과 같다 — uid 로 읽는다
CREATE TABLE member_profile (
    user_id           VARCHAR(255) PRIMARY KEY,
    -- 실명. 서비스가 코드 포인트로 1~20자를 센다 (이모지 1개 = 1자, VARCHAR 와 같은 셈)
    real_name         VARCHAR(20)  NOT NULL,
    birth_date        DATE         NOT NULL,
    -- 16가지 + 잘 몰라요. 오타 난 값이 들어가면 다른 멤버 화면이 그 카드를 못 그린다
    mbti              VARCHAR(7)   NOT NULL CHECK (mbti IN (
        'ISTJ', 'ISFJ', 'INFJ', 'INTJ', 'ISTP', 'ISFP', 'INFP', 'INTP',
        'ESTP', 'ESFP', 'ENFP', 'ENTP', 'ESTJ', 'ESFJ', 'ENFJ', 'ENTJ', 'UNKNOWN')),
    room_count        INTEGER      NOT NULL CHECK (room_count BETWEEN 0 AND 9999),
    -- 고른 순서대로 쉼표로 잇는다. 목록 검사는 서비스가 한다 — 장르를 더할 때마다 이행을 만들지 않으려고
    genres            VARCHAR(60)  NOT NULL,
    life_theme_name   VARCHAR(40)  NOT NULL,
    life_theme_branch VARCHAR(40),
    life_theme_key    VARCHAR(80),
    updated_at        TIMESTAMPTZ  NOT NULL
);

-- 승인된 때. decided_at 은 역할 바꾸기·내보내기에도 새로 찍혀서 승인일로 못 쓴다 (D35).
-- 기존 승인 멤버는 decided_at 으로 채운다 — 역할을 바꾼 적이 있으면 그 날짜가 들어간다(계약이 받아들였다)
ALTER TABLE club_member ADD COLUMN approved_at TIMESTAMPTZ;
UPDATE club_member SET approved_at = decided_at WHERE status = 'APPROVED';

-- 랭킹이 날짜 범위로 읽는다
CREATE INDEX idx_played_on ON played_theme (played_on);
