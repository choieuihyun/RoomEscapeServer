#!/usr/bin/env bash
# 새 커밋이 올라오면 스스로 받아 다시 띄운다. 5분마다 크론이 부른다.
#
# **왜 GitHub Actions 가 아니라 서버가 당겨오나 (2026-08-31 결정)**
#   Actions 로 밀어넣으려면 **SSH 개인키를 GitHub Secrets 에 맡겨야 한다.**
#   bootstrap 이 이 서버를 키 전용으로 잠가 뒀는데, 그 키를 남의 시스템에 두는 것은
#   배포.md 의 "API 키 만들지 않기 — 새면 몇 초 만에 털린다" 와 같은 결에서 피한다.
#   당겨오면 **밖으로 나가는 자격증명이 하나도 없다.** 서버가 GitHub 을 읽기만 한다.
#
#   대신 약점이 있다 — **실패해도 아무 화면에도 안 뜬다.** 그래서 아래를 한다:
#     · 결과를 STATUS 파일 한 줄로 남긴다 (사람이 볼 자리)
#     · journald 에 태그로 남긴다  journalctl -t roomescape-autodeploy
#     · 빌드가 깨지면 **돌던 컨테이너를 안 건드린다** (아래 "빌드 먼저" 참고)
set -euo pipefail

APP_DIR="$HOME/roomescape"
STATUS="$APP_DIR/.autodeploy-status"
LOG="$APP_DIR/autodeploy.log"
COMPOSE="docker-compose.prod.yml"
TAG=roomescape-autodeploy

# 재빌드가 필요한 경로. **문서만 바뀐 커밋에 10분짜리 ARM 빌드를 돌리지 않는다.**
# 여기 빠뜨리면 코드가 바뀌었는데 안 올라간다 — 늘리는 쪽이 안전하다
CODE_PATHS='^(src/|gradle/|build\.gradle\.kts|settings\.gradle\.kts|gradlew|Dockerfile|Caddyfile|docker-compose)'

say() {
  printf '%s %s\n' "$(date '+%F %T')" "$*" >> "$LOG"
  logger -t "$TAG" -- "$*" 2>/dev/null || true
}
status() { printf '%s | %s | %s\n' "$(date '+%F %T')" "$(git -C "$APP_DIR" rev-parse --short HEAD)" "$*" > "$STATUS"; }

# 로그가 자라기만 하면 언젠가 디스크를 채운다 (배포.md "요금이 나가는 자리").
# 회전 도구를 붙일 만큼 큰 로그가 아니라 그냥 자른다
[ -f "$LOG" ] && [ "$(stat -c%s "$LOG" 2>/dev/null || echo 0)" -gt 1000000 ] && tail -n 500 "$LOG" > "$LOG.tmp" && mv "$LOG.tmp" "$LOG"

cd "$APP_DIR" || { say "✗ $APP_DIR 이 없다"; exit 1; }

git fetch --quiet origin main || { say "✗ git fetch 실패 (네트워크?)"; status "fetch 실패"; exit 1; }

LOCAL=$(git rev-parse HEAD)
REMOTE=$(git rev-parse origin/main)
[ "$LOCAL" = "$REMOTE" ] && exit 0        # 조용히 끝낸다. 5분마다 로그를 남길 이유가 없다

# **fast-forward 가 아니면 손대지 않는다.** 서버에서 뭔가 고쳐 놨거나 히스토리가 갈렸다는 뜻이고,
# 그걸 자동으로 푸는 것보다 사람이 보는 편이 낫다
if ! git merge-base --is-ancestor "$LOCAL" "$REMOTE"; then
  say "✗ fast-forward 가 아니다 — 손으로 확인할 것 (로컬=$LOCAL 원격=$REMOTE)"
  status "FF 아님 · 멈춤"
  exit 1
fi

CHANGED=$(git diff --name-only "$LOCAL" "$REMOTE")
git pull --quiet --ff-only origin main

# 이 스크립트 자신이 바뀌었으면 다음 실행부터 새 것이 돈다.
# **지금 실행 중인 것을 갈아치우지 않는다** — bash 가 파일을 읽어 가며 실행해서 반쯤 섞인다
if [ -f deploy/autodeploy.sh ] && ! cmp -s deploy/autodeploy.sh "$HOME/.roomescape/autodeploy.sh"; then
  install -m 700 deploy/autodeploy.sh "$HOME/.roomescape/autodeploy.sh"
  say "· autodeploy.sh 갱신됨 — 다음 실행부터 적용된다"
fi

if ! grep -qE "$CODE_PATHS" <<< "$CHANGED"; then
  say "· 문서만 바뀜 → 재빌드 없이 $(git rev-parse --short HEAD) 로 갱신"
  status "문서만 · 재빌드 없음"
  exit 0
fi

say "▶ 코드 변경 감지 → 빌드 시작 ($(git rev-parse --short HEAD))"

# **빌드를 먼저, 따로 한다.** `up -d --build` 는 빌드와 교체가 한 덩어리라
# 컴파일이 깨지는 순간을 돌던 컨테이너와 분리할 수 없다.
# 여기서 실패하면 **이전 컨테이너가 그대로 계속 돈다**
if ! sudo docker compose -f "$COMPOSE" build >> "$LOG" 2>&1; then
  say "✗ 빌드 실패 — 이전 버전이 계속 돈다. tail -50 $LOG"
  status "빌드 실패 · 이전 버전 유지"
  exit 1
fi

sudo docker compose -f "$COMPOSE" up -d >> "$LOG" 2>&1 || {
  say "✗ 기동 실패 — tail -50 $LOG"
  status "기동 실패"
  exit 1
}

# bootstrap 과 같은 방식으로 확인한다 — Caddy 가 도메인 블록만 가져서
# `Host: localhost` 로는 응답하지 않는다. `--resolve` 로 DNS 를 거치지 않고 자기 자신에게 묻는다
DOMAIN=$(grep -oP '(?<=^DOMAIN=).*' .env 2>/dev/null || echo "")
for _ in $(seq 1 40); do
  code=$(curl -sk -o /dev/null -w '%{http_code}' --resolve "${DOMAIN}:443:127.0.0.1" \
    "https://${DOMAIN}/api/branches" 2>/dev/null || echo 000)
  [ "$code" = "200" ] && break
  sleep 5
done

if [ "${code:-000}" = "200" ]; then
  say "✅ 배포 완료 — $(git rev-parse --short HEAD)"
  status "성공"
else
  # 빌드는 됐는데 안 뜬다. **되돌리지 않는다** — 자동 롤백은 오락가락할 수 있고,
  # 무엇이 깨졌는지 사람이 보는 편이 낫다. 옛 이미지는 도커에 남아 있다
  say "✗ 배포했는데 응답이 없다 (code=$code) — sudo docker compose -f $COMPOSE logs --tail 50 app"
  status "응답 없음 · 확인 필요"
fi
