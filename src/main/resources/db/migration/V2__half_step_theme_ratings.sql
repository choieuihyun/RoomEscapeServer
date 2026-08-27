-- 공포도·난이도는 0.5 단위다. INTEGER 로 받아서 소수점을 잃고 있었다.
--
--   공포   "3.5"   → 35      (숫자만 남기던 파서)
--   공포   "0.5"   →  5      ← "거의 안 무섭다" 가 "최고 공포" 로 뒤집혔다
--   난이도 size25  → 25      (reservation.css 기준 2.5 다)
--
-- 기존 값은 **전부 버린다.** 저장된 `5` 가 진짜 5.0 인지 뒤집힌 0.5 인지
-- 구분할 방법이 없어서, 남겨 두면 틀린 값을 옳은 값인 척 계속 들고 있게 된다.
-- 다음 수집 한 바퀴가 다시 채운다 (테마 메타데이터라 회차·전이에는 영향이 없다).

ALTER TABLE theme ALTER COLUMN horror_level TYPE DOUBLE PRECISION USING NULL;
ALTER TABLE theme ALTER COLUMN difficulty   TYPE DOUBLE PRECISION USING NULL;
