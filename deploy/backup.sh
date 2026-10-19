#!/usr/bin/env bash
# 매일 한 번 DB 를 통째로 떠 둔다. 크론이 부른다 (배포.md "DB 백업").
#
# **왜 지금 생겼나 (2026-10-19)** — 동호회 기능이 들어오면서 이 DB 에 처음으로
# **다시 긁어 올 수 없는 데이터**가 생긴다. 회차는 날아가도 다음 바퀴에 다시 모이지만,
# 누가 멤버인지 · 누가 승인했는지는 매장 사이트 어디에도 없다.
#
# **크론에서 돈다는 것을 전제로 짠다.** 크론은 PATH 가 거의 비어 있고, 현재 디렉터리가 홈이고,
# 실패해도 아무 화면에 안 뜬다. 그래서:
#   · 경로는 전부 절대 경로, 도커는 `sudo docker` (autodeploy.sh 와 같은 방식)
#   · **임시 파일에 받고, 성공했을 때만 이름을 바꾼다** — 중간에 죽으면 반쪽짜리 덤프가
#     "어제 백업" 처럼 남는다. 되살릴 때 가서야 깨진 것을 알게 되는 것이 백업의 최악이다
#   · 결과는 journald 에 남긴다  journalctl -t roomescape-backup
set -euo pipefail
export PATH=/usr/local/bin:/usr/bin:/bin

APP_DIR="$HOME/roomescape"
# **저장소 밖에 둔다.** ~/roomescape 는 git 작업 디렉터리이자 도커 빌드 컨텍스트라,
# 안에 두면 빌드할 때마다 덤프 14개가 도커 데몬으로 실려 가고 `git status` 도 더러워진다
BACKUP_DIR="$HOME/backups/roomescape"
COMPOSE="docker-compose.prod.yml"
KEEP_DAYS=14
TAG=roomescape-backup

say() { logger -t "$TAG" -- "$*" 2>/dev/null || true; printf '%s %s\n' "$(date '+%F %T')" "$*"; }

# 닉네임·승인 기록이 들어 있다. 이 기계의 다른 사용자가 읽을 이유가 없다
umask 077
mkdir -p "$BACKUP_DIR"
cd "$APP_DIR" || { say "✗ $APP_DIR 이 없다"; exit 1; }

OUT="$BACKUP_DIR/roomescape-$(date +%F).dump"
TMP="$OUT.partial"
trap 'rm -f "$TMP"' EXIT

# -Fc: pg_restore 가 읽는 압축 형식. 표 하나만 골라 되살릴 수도 있다 (배포.md)
# -T: 크론에는 터미널이 없다. 빼면 "the input device is not a TTY" 로 죽는다
if ! sudo docker compose -f "$COMPOSE" exec -T db pg_dump -U roomescape -Fc roomescape > "$TMP"; then
  say "✗ pg_dump 실패 — 어제 것까지는 남아 있다 ($BACKUP_DIR)"
  exit 1
fi

# 0바이트 덤프는 실패다. pg_dump 가 0 으로 끝나도 파이프 저편이 비는 경우가 있다
if [ ! -s "$TMP" ]; then
  say "✗ 덤프가 비어 있다 — 저장하지 않는다"
  exit 1
fi

mv "$TMP" "$OUT"
trap - EXIT

# 14일 지난 것만 지운다. **방금 만든 것이 성공한 뒤에** 지운다 — 순서가 바뀌면
# 덤프가 계속 실패하는 동안 옛 백업까지 하나씩 사라진다
find "$BACKUP_DIR" -name 'roomescape-*.dump' -mtime +"$KEEP_DAYS" -delete

say "✓ $(basename "$OUT") $(du -h "$OUT" | cut -f1) · 보관 $(find "$BACKUP_DIR" -name 'roomescape-*.dump' | wc -l)개"
