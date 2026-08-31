#!/usr/bin/env bash
# 갓 만든 우분투 서버 하나를 이 서비스가 도는 상태까지 올린다.
#
#   ssh <서버> 'bash -s' -- <도메인> [DuckDNS토큰] < deploy/bootstrap.sh
#
# 예)
#   ssh roomescape 'bash -s' -- roomescape.duckdns.org a1b2c3d4-... < deploy/bootstrap.sh
#
# 여러 번 실행해도 안전하다(멱등). 실패하면 그 자리에서 멈춘다.
#
# 하는 일: 도커 · 방화벽 · SSH 잠그기 · 자동 보안 업데이트 · fail2ban
#          · DuckDNS · 코드 · .env · 빌드 · 기동 · 응답 확인
#
# ⚠️ 오라클 클라우드는 방화벽이 두 겹이다. 이 스크립트는 서버 안쪽(iptables)만 연다.
#    바깥쪽(VCN 보안 목록)은 콘솔에서 열어야 한다 — 아래 안내가 출력된다.

set -euo pipefail

DOMAIN="${1:-}"
DUCKDNS_TOKEN="${2:-}"
REPO="${REPO:-https://github.com/choieuihyun/RoomEscapeServer.git}"
APP_DIR="${APP_DIR:-$HOME/roomescape}"

say() { printf '\n\033[1;34m▶ %s\033[0m\n' "$*"; }
ok()  { printf '  \033[32m✓\033[0m %s\n' "$*"; }
die() { printf '\n\033[31m✗ %s\033[0m\n' "$*" >&2; exit 1; }

# `unattended-upgrades` 가 매일 알아서 apt 를 돌린다 — 이 스크립트가 켜 준 것이다(아래 (2)).
# 하필 그때 배포하면 dpkg 잠금이 겹쳐 `Could not get lock ...` 로 **중간에 죽는다.**
# 2026-08-31 재배포가 실제로 여기서 멈췄다. 앱은 안 건드린 뒤라 피해는 없었지만,
# "몇 번을 돌려도 안전하다" 는 약속이 시간대에 따라 깨지고 있었던 것이다.
#
# 기다리면 되는 일이라 기다린다. `DPkg::Lock::Timeout` 은 잠금을 만나면
# 즉시 실패하는 대신 그 초만큼 기다린다 (apt 2.0+ · 우분투 22.04 이상).
apt_get() { sudo apt-get -o DPkg::Lock::Timeout=600 "$@"; }

[ -n "$DOMAIN" ] || die "도메인을 인자로 넘겨야 한다.  예: bash -s -- roomescape.duckdns.org <토큰>"
[ "$(id -u)" -ne 0 ] || die "root 말고 일반 사용자(ubuntu)로 실행할 것. 필요할 때만 sudo 를 쓴다."

# ── 1. 패키지 ────────────────────────────────────────────
say "패키지 준비"
apt_get update -qq
apt_get install -y -qq ca-certificates curl git iptables-persistent >/dev/null
ok "기본 패키지"

# ── 2. 도커 ──────────────────────────────────────────────
say "도커"
if command -v docker >/dev/null 2>&1; then
  ok "이미 설치됨 ($(docker --version | cut -d, -f1))"
else
  # 공식 apt 저장소를 쓴다. curl | sh 보다 느리지만 무엇이 설치되는지 보인다
  sudo install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg |
    sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
  sudo chmod a+r /etc/apt/keyrings/docker.gpg
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] \
https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" |
    sudo tee /etc/apt/sources.list.d/docker.list >/dev/null
  apt_get update -qq
  apt_get install -y -qq docker-ce docker-ce-cli containerd.io \
    docker-buildx-plugin docker-compose-plugin >/dev/null
  ok "설치 완료"
fi

# sudo 없이 docker 를 쓰려면 그룹에 넣어야 한다. 적용은 다음 로그인부터다
if ! id -nG "$USER" | grep -qw docker; then
  sudo usermod -aG docker "$USER"
  ok "docker 그룹에 추가 (다음 접속부터 sudo 없이)"
fi
sudo systemctl enable --now docker >/dev/null 2>&1 || true

# ── 3. 서버 안쪽 방화벽 ──────────────────────────────────
# 오라클 우분투 이미지는 iptables 로 22 말고 전부 막아 둔다.
# 이걸 모르면 "보안 목록은 열었는데 왜 접속이 안 되지" 로 한참 헤맨다.
say "방화벽 (서버 안쪽)"

# REJECT/DROP 규칙보다 **앞에** 넣어야 한다. 뒤에 붙으면 그 전에 막혀서 아무 효과가 없다.
# 위치를 숫자로 박아 두면 이미지의 기본 규칙이 하나만 달라져도 엉뚱한 자리에 들어간다 —
# 그때 증상이 "열었는데 접속이 안 된다" 라서 원인을 찾기가 아주 어렵다. 매번 찾아서 넣는다.
open_port() {
  local port="$1" pos
  pos=$(sudo iptables -L INPUT --line-numbers -n | awk '$2=="REJECT"||$2=="DROP"{print $1; exit}')
  if [ -n "$pos" ]; then
    sudo iptables -I INPUT "$pos" -p tcp --dport "$port" -m state --state NEW -j ACCEPT
  else
    sudo iptables -A INPUT -p tcp --dport "$port" -m state --state NEW -j ACCEPT
  fi
}

for port in 80 443; do
  if sudo iptables -C INPUT -p tcp --dport "$port" -m state --state NEW -j ACCEPT 2>/dev/null; then
    ok "$port 이미 열림"
  else
    open_port "$port"
    ok "$port 열림"
  fi
done
sudo netfilter-persistent save >/dev/null 2>&1 && ok "재부팅 후에도 유지되게 저장"

# ── 4. 서버 잠그기 ───────────────────────────────────────
# 인터넷에 붙은 기계는 몇 분 안에 스캔당한다. 여기서 하는 일은 셋이다.
say "보안"

# (1) SSH 비밀번호 로그인 차단
#     **키가 있는 걸 확인하고 나서만** 끈다. 확인 없이 끄면 키 설정이 안 된 기계에서
#     자기 자신을 영영 잠가 버린다 — 클라우드 콘솔로도 못 들어간다.
KEYS="$HOME/.ssh/authorized_keys"
if [ -s "$KEYS" ]; then
  sudo tee /etc/ssh/sshd_config.d/99-roomescape.conf >/dev/null <<'SSHCONF'
# 비밀번호로는 못 들어온다. 키만 받는다
PasswordAuthentication no
KbdInteractiveAuthentication no
PermitRootLogin no
SSHCONF
  # 설정이 깨졌는데 재시작하면 그때 SSH 가 안 뜬다. 먼저 검사한다.
  # /run/sshd 가 없으면 설정과 무관하게 검사가 실패해서 멀쩡한 설정을 되돌리게 된다
  sudo mkdir -p /run/sshd
  if sudo sshd -t 2>/dev/null; then
    sudo systemctl reload ssh 2>/dev/null || sudo systemctl restart ssh
    ok "SSH — 키만 허용 (비밀번호·root 로그인 차단)"
  else
    sudo rm -f /etc/ssh/sshd_config.d/99-roomescape.conf
    printf '  \033[33m!\033[0m SSH 설정 검사 실패 — 되돌렸다. 수동 확인 필요\n'
  fi
else
  printf '  \033[33m!\033[0m authorized_keys 가 비었다 — 비밀번호 로그인을 끄지 않는다\n'
  printf '    (끄면 이 기계에 다시 못 들어온다. 키를 넣고 다시 실행할 것)\n'
fi

# (2) 자동 보안 업데이트
#     안 하면 알려진 취약점이 그대로 남는다. 잊어버려도 돌아가는 게 서버의 값이라
#     "가끔 손으로 apt upgrade" 는 계획이 아니다.
apt_get install -y -qq unattended-upgrades >/dev/null
sudo tee /etc/apt/apt.conf.d/20auto-upgrades >/dev/null <<'AUTOUP'
APT::Periodic::Update-Package-Lists "1";
APT::Periodic::Unattended-Upgrade "1";
AUTOUP
# 커널 패치는 재부팅해야 적용된다. 새벽 4시에, 필요할 때만.
# 컨테이너는 restart: unless-stopped 라 부팅 후 알아서 돌아온다
sudo tee /etc/apt/apt.conf.d/52roomescape-reboot >/dev/null <<'REBOOT'
Unattended-Upgrade::Automatic-Reboot "true";
Unattended-Upgrade::Automatic-Reboot-Time "04:00";
REBOOT
ok "자동 보안 업데이트 (필요 시 04:00 재부팅)"

# (3) fail2ban — SSH 무차별 대입을 차단한다
apt_get install -y -qq fail2ban >/dev/null
sudo tee /etc/fail2ban/jail.d/sshd.local >/dev/null <<'JAIL'
[sshd]
enabled  = true
# 우분투 24.04 는 SSH 로그가 파일이 아니라 저널로 간다
backend  = systemd
maxretry = 5
bantime  = 1h
JAIL
# 설정이 깨져 있으면 fail2ban 이 조용히 안 뜬다. 켜기 전에 검사한다
if sudo fail2ban-client -t >/dev/null 2>&1; then
  sudo systemctl enable --now fail2ban >/dev/null 2>&1 || true
  ok "fail2ban (5회 실패 → 1시간 차단)"
else
  sudo rm -f /etc/fail2ban/jail.d/sshd.local
  printf '  \033[33m!\033[0m fail2ban 설정 검사 실패 — 되돌렸다. 수동 확인 필요\n'
fi

# ── 5. DuckDNS (선택) ────────────────────────────────────
if [ -n "$DUCKDNS_TOKEN" ]; then
  say "DuckDNS"
  SUB="${DOMAIN%%.duckdns.org}"
  resp=$(curl -fsS "https://www.duckdns.org/update?domains=${SUB}&token=${DUCKDNS_TOKEN}&ip=")
  [ "$resp" = "OK" ] || die "DuckDNS 갱신 실패 (응답: $resp). 서브도메인과 토큰을 확인할 것"
  ok "$DOMAIN → 이 서버 IP"

  # 인스턴스를 껐다 켜면 IP 가 바뀔 수 있다. 5분마다 맞춰 둔다
  mkdir -p "$HOME/.duckdns"
  printf 'curl -fsS "https://www.duckdns.org/update?domains=%s&token=%s&ip=" >/dev/null\n' \
    "$SUB" "$DUCKDNS_TOKEN" > "$HOME/.duckdns/update.sh"
  chmod 700 "$HOME/.duckdns/update.sh"
  # 갓 만든 기계는 크론탭이 아예 없어서 `crontab -l` 이 1 로 끝나고,
  # 일치하는 줄이 없으면 `grep -v` 도 1 로 끝난다. `set -euo pipefail` 이 둘 다
  # 실패로 보고 스크립트를 죽인다 — 정상인 상황이라 `|| true` 로 받아 준다
  ( { crontab -l 2>/dev/null || true; } | grep -v duckdns/update.sh || true
    echo "*/5 * * * * bash $HOME/.duckdns/update.sh" ) | crontab -
  ok "5분마다 IP 자동 갱신 (크론)"
fi

# ── 6. 코드 ──────────────────────────────────────────────
say "코드"
if [ -d "$APP_DIR/.git" ]; then
  git -C "$APP_DIR" pull --ff-only
  ok "최신으로 갱신"
else
  git clone --depth 1 "$REPO" "$APP_DIR"
  ok "$APP_DIR 에 받음"
fi

# ── 7. 비밀값 ────────────────────────────────────────────
say "환경 설정"
ENV_FILE="$APP_DIR/.env"
if [ -f "$ENV_FILE" ]; then
  ok ".env 이미 있음 (덮어쓰지 않는다)"
  # 도메인이 바뀌었을 수 있으니 그것만 맞춘다
  if ! grep -q "^DOMAIN=${DOMAIN}$" "$ENV_FILE"; then
    sed -i "s|^DOMAIN=.*|DOMAIN=${DOMAIN}|" "$ENV_FILE"
    ok "DOMAIN 을 $DOMAIN 으로 갱신"
  fi
else
  # DB 비밀번호는 사람이 볼 일이 없다. 여기서 만들고 .env 에만 둔다
  cat > "$ENV_FILE" <<EOF
DB_PASSWORD=$(openssl rand -base64 30 | tr -d '/+=' | head -c 32)
DOMAIN=${DOMAIN}
API_CORS_ORIGINS=https://choieuihyun.github.io
EOF
  chmod 600 "$ENV_FILE"
  ok ".env 생성 (DB 비밀번호 자동 생성)"
fi

# ── 8. 기동 ──────────────────────────────────────────────
say "빌드 & 기동 (첫 빌드는 몇 분 걸린다)"
cd "$APP_DIR"
sudo docker compose -f docker-compose.prod.yml up -d --build

# ── 9. 확인 ──────────────────────────────────────────────
say "확인"
for i in $(seq 1 60); do
  # **도메인으로 물어야 한다.** Caddy 는 `{$DOMAIN}` 사이트 블록만 갖고 있어서
  # `Host: localhost` 로 오는 요청에는 응답하지 않는다 — 앱이 멀쩡해도 실패로 보인다.
  # `--resolve` 로 DNS 를 거치지 않고 자기 자신에게 물어, 전파를 기다리지 않는다
  code=$(curl -sk -o /dev/null -w '%{http_code}' --resolve "${DOMAIN}:443:127.0.0.1" \
    "https://${DOMAIN}/api/branches" 2>/dev/null || echo 000)
  [ "$code" = "200" ] && break
  sleep 5
done
[ "${code:-000}" = "200" ] || {
  echo
  sudo docker compose -f docker-compose.prod.yml logs --tail 40
  die "앱이 응답하지 않는다. 위 로그를 확인할 것"
}
ok "앱 응답 정상 (서버 내부에서 https://${DOMAIN} → 200)"

echo
printf '\033[1;32m배포 끝났다.\033[0m\n\n'
printf '  서버 내부 확인 :  curl -sk https://localhost/api/branches\n'
printf '  바깥에서 확인   :  curl -s https://%s/api/branches\n' "$DOMAIN"
printf '  로그            :  cd %s && sudo docker compose -f docker-compose.prod.yml logs -f app\n\n' "$APP_DIR"
cat <<'NOTE'
⚠️ 바깥에서 접속이 안 되면 클라우드 쪽 방화벽이 아직 닫혀 있는 것이다.
   이 스크립트는 서버 안쪽(iptables)만 열 수 있다.

   오라클  : 네트워킹 → VCN → 보안 목록 → 수신 규칙 추가
             소스 0.0.0.0/0, TCP, 대상 포트 80 과 443 각각
   Hetzner : 기본적으로 열려 있다. 방화벽을 따로 만들었다면 거기에 80/443 추가

   HTTPS 인증서는 80 포트가 열려야 발급된다 — 80 을 빼먹으면 인증서부터 실패한다.
NOTE
