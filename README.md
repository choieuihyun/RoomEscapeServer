# RoomEscapeServer

방탈출 예약 사이트를 주기적으로 지켜보다가 **`예약 불가 → 예약 가능` 으로 바뀐 순간(취소표)** 을 잡아내는 서버.

수집한 시간표는 두 곳으로 나간다.

1. **알림** — 감시를 걸어 둔 자리가 풀리면 알린다 *(서버 완료 · 브라우저 등록만 남음)*
2. **[Floduler](https://github.com/choieuihyun/RoomEscapeScheduler) 피드** — 방탈출 연방 시간표 계산기에 회차와 **매진 여부**를 제공

Kotlin 2.3 · Spring Boot 4.1 · PostgreSQL 17 · JDK 21

**지금 돌고 있다 — https://floduler.duckdns.org** (오라클 도쿄 · 한 바퀴 약 7.6분 · 실측 2026-10-07)

```
플레이33       4지점  9테마     지점 × 날짜 = 요청 1회   HTML   disabled 속성
키이스케이프   11지점 32테마     테마 × 날짜 = 요청 1회   JSON   enable Y/N
토끼굴          1지점  2테마     지점 × 날짜 = 요청 1회   HTML   label 문구
지구별          3지점 21테마     지점 × 날짜 = 요청 1회   HTML   label 문구
포인트나인      3지점  9테마     지점 × 날짜 = 요청 1회   HTML   href 존재 여부
비트포비아      9지점 32테마     지점 × 날짜 = 요청 1회   HTML   href 존재 여부
제로월드        3지점 25테마     테마 × 날짜 = 요청 1회   조각   href + 사이트 집계 대조
제로월드 다이브  1지점  2테마     테마 × 날짜 = 요청 1회   조각   href + 사이트 집계 대조
플레이포인트랩   6지점 25테마     지점 × 날짜 = POST 1회   조각   available 클래스 (마스터키 건대 포함)
다이아에그       2지점 11테마     지점 × 날짜 = 요청 1회   HTML   href 존재 여부
신비웹 GET 8곳  10지점 29테마     지점 × 날짜 = 요청 1회   HTML   href 존재 여부 (공통 어댑터)
프랭크           1지점  3테마     테마 × 날짜 + 달력       조각   javascript 링크
코드케이         2지점 17테마     지점 × 날짜 = 요청 1회   HTML   예약 링크 (ASP · cp949)
싸인이스케이프   3지점  6테마     테마 × 날짜 = 요청 1회   HTML   예약 링크 (ASP · cp949)
룸즈에이         1지점  5테마     지점 1회 = 창 전체       JSON   can_book (Reserv)
플레이더월드     1지점  5테마     지점 1회 = 창 전체       JSON   can_book (Reserv · 오늘 지난 회차는 불가)
오늘의 한 페이지 1지점  2테마     지점 1회 = 창 전체       JSON   can_book (Reserv)
스머프           2지점  2테마     지점 × 날짜 + 날짜 목록  조각   링크 (관 4·5개를 시각으로 합침)
서울이스케이프룸 1지점  1테마     지점 × 날짜 = 요청 1회   HTML   예약 버튼 클래스 + 숨은 예약 정보
머더파커         4지점 17테마     지점 × 날짜 = POST 1회   HTML   예약 링크 (ASP 변형 · cp949)
WP booking 2곳   2지점  5테마     지점 × 날짜 = POST 1회   조각   submit 클래스 · 빈 응답 = 그날 매진 (딥띵커 · 쇼룸404)
                                                    ─────────────────────
                                                    30매장 71지점  (2026-10-22 14:48 홍대 · 건대 배포 · 첫 바퀴에 운영 서버를 막은 홍대 6지점은 뺐다)

  ── 배포 전 (`seoul` 브랜치 · 2026-10-27 서울 나머지 · 운영 서버엔 아직 없다) ──
신비웹 GET +6곳   6지점 26테마     지점 × 날짜 = 요청 1회   HTML   href 존재 여부 (상상의문 · 에필로그 · 호텔 레토 · 나의신방 · 클레버타운 · 탈출브라더스)
이스케이프시티    3지점 14테마     지점 × 날짜 = 요청 1회   HTML   예약 링크 (ASP · cp949 · 지점은 켜진 탭으로)
코드이스케이프    1지점  3테마     지점 × 날짜 = 요청 1회   HTML   예약 링크 · rev_n.png (신비웹 옛 테이블 세대)
머더파커 +2 · 룸즈에이 +1 · 플레이포인트랩 +1   (잠실 2 · 대학로 · 노원 — 위 표의 어댑터 그대로)
                                                    ─────────────────────
                                                    38매장 85지점  (배포하면 위 표에 합친다)
```

**매장별 요청 형식·판정 근거·함정은 [방탈출 지점 요청_응답 정리.md](방탈출%20지점%20요청_응답%20정리.md) 에 있다.**
어댑터를 쓰기 전에 거기부터 적는다 — 조사가 코드보다 앞선다.

---

## 왜 서버가 필요한가

이 서비스의 본질은 **변화 감지**고, 변화를 감지하려면 **과거를 기억해야 한다.**
"아까는 매진이었는데 지금 풀렸다" 를 알려면 아까 상태가 어딘가 남아 있어야 하는데,
앱은 꺼지면 그만이고 사용자마다 따로 돌면 매장 사이트를 사람 수만큼 두드리게 된다.

---

## 구조

```
  ⑥ Scheduler ─ 한 바퀴 끝나고 5분 뒤 다시 (실제 간격 약 7.6분) 아래를 실행
                      │
  play33.kr     ──▶ HTML ┐
  keyescape.com ──▶ JSON ┼─ ①Client ─ ②Parser ─ ③Crawler(검증) ─ 어댑터
  그 외 2곳      ──▶ HTML ┘                                         │
 (매장 사이트 9곳 · 사이트끼리는 동시에, 같은 사이트로는 한 줄로)     │
                                                                    ↓
                                                            ④ Repository
                                                            이전 상태와 비교 후 저장
                                                              │         │
                                       불가→가능 전이만 ───────┘         └── 조회 요청
                                                     ↓                        ↓
                                              ⑤ Notifier                 ⑦ Api
                                            (로그 | FCM 푸시)                  ↓
                                                     ↓                    Floduler
                                                사용자 폰
```

**속도를 정하는 단위는 지점이 아니라 서버다.** 요청 사이 1.2초를 쉬어 **한 호스트에 초당 1회를
넘기지 않는다.** 플레이33은 4지점이 전부 `play33.kr` 한 대고, 지점은 `?branch=N` 파라미터일 뿐이다.
지점별로 동시에 긁으면 그 서버 한 대에 초당 4회가 간다.

그래서 **같은 사이트로 가는 요청은 한 줄로 세우고, 사이트끼리만 동시에** 돈다.

```
플레이33      지점 × 날짜 = 요청 1회    4지점  9테마 → 전량  28요청
키이스케이프   테마 × 날짜 = 요청 1회   11지점 32테마 → 전량 224요청
토끼굴        지점 × 날짜 = 요청 1회    1지점  2테마 → 전량   7요청
지구별        지점 × 날짜 = 요청 1회    3지점 21테마 → 전량  21요청
포인트나인     지점 × 날짜 = 요청 1회    3지점  9테마 → 전량  21요청
비트포비아     지점 × 날짜 = 요청 1회    9지점 32테마 → 전량  63요청
제로월드 A/B   테마 × 날짜 = 요청 1회    4지점 27테마 → 날짜당 27요청  ← 제일 비싸다
```

**요청 단위가 한 바퀴 시간을 정한다** — 지점 수가 아니다. 키이스케이프는 테마마다 물어야 해서
지점이 11개인 것보다 그쪽이 크다.

그래서 **호스트를 늘리는 건 거의 공짜고, 한 호스트 안에서 요청이 느는 것이 비싸다.**
지구별(3지점)을 넣었을 때 한 바퀴가 **120초에서 그대로**였다 — 키이스케이프와 동시에 돌아서다.

날짜는 **요일로 등급을 나눠** 긁는다 — 토·일 매 바퀴 / 금 2바퀴마다 / 그 외 순환.
주말 자리는 풀리면 몇 분 안에 채가고 평일은 여유가 있다. 매 바퀴 평균 4.5일치라 전량의 3분의 1이다.

```
실측 2026-10-07   한 바퀴 436~514초(평균 455) · 9사이트 · 276~363요청 · 206바퀴
  토·일    매 바퀴     약 7.6분
  금       2바퀴마다   약 15분
  평일     4바퀴마다   약 30분
  먼 창    8바퀴마다   약 61분
```

**감시가 걸린 자리는 5~7분 사이 무작위로 따로 본다** (아키텍처 D27 · D44 · 2026-10-22 14:48 운영 반영 — 전엔 2분 고정).
위 표는 "아무도 안 기다리는 자리" 의 주기다. 누가 기다리는 자리는 그 지점·날짜만 한 번 더 묻는다 —
감시가 없으면 0건, 한 번에 최대 5건, 새벽 01~07시는 쉬고, 실패하거나 느리면 그 매장은 30분 쉰다.

> 주기를 **바퀴 수로** 적는다. 한 바퀴가 고정이 아니어서다 — `fixedDelay` 라 "끝나고 5분" 이고
> 수집 자체가 2.3~2.8분 걸린다. 매장이 늘면 분은 또 바뀌지만 바퀴 수는 안 바뀐다.
>
> 바퀴 번호는 **DB 카운터**가 센다(V5). 2026-09-01 이전에는 시계에서 뽑았는데, 나누는 값이
> 설정값(5분)이라 실제 주기와 어긋나 번호가 1~2씩 뛰었고 **먼 날짜가 굶었다**
> ([아키텍처.md](아키텍처.md) D14 정정).

```
실측 2026-08-28   한 바퀴 120초 · 사이트 4곳 · 3일치 · 120요청 · 실패 0
실측 2026-08-31   한 바퀴 162초 · 사이트 5곳 · 4일치 · 172요청 · 실패 0
실측 2026-08-31   한 바퀴 171초 · 사이트 7곳 · 5일치 · 344요청 · 실패 0   ← 8매장 35지점
```

⚠️ **이 둘을 빼서 "포인트나인 때문에 42초 늘었다" 고 읽으면 안 된다.** 날짜 수가 다르다.
한 바퀴가 몇 일치인지는 그 바퀴가 언제 도느냐에 따라 달라진다(계층 폴링).

**한 바퀴 시간은 `가장 느린 호스트의 요청 수 × 날짜 수` 다.**

```
2026-08-31 · 5일치 · 총 344요청
  제로월드     27테마 × 5일 = 135요청 × 1.2초 = 162초   ← 지금 여기가 한 바퀴를 정한다
  키이스케이프  32테마 × 4일 = 128요청 × 1.2초 = 154초
  비트포비아    9지점 × 4일 =  36요청          =  43초
  나머지 넷                    45요청           ← 전부 저 162초 안에서 같이 돈다
                                                            실측 한 바퀴 171초
```

**호스트를 늘리는 건 거의 공짜다.** 비트포비아 36요청을 넣었을 때 한 바퀴는 121초 → 121초였다.
비싼 것은 **한 호스트 안에서 요청이 느는 것**이고, 제로월드가 그 경우다 (테마마다 요청 1건).

> **날짜 수가 다르면 두 바퀴를 빼서 비교하지 않는다.** `사이트 N곳 · M일치` 를 같이 본다.

---

## 조회 API

```
GET /api/branches                        지점 목록 + 고를 수 있는 날짜
GET /api/schedule?branch=&date=          그날 전 테마 회차 + 매진 여부 + 메타데이터
```

시각은 **자정부터의 분**(`10:35` → `635`)으로 내려준다 — 쓰는 쪽 형식에 맞춘 것이다.
**매진 회차도 빼지 않는다.** 계산에서 거르는 것과 화면에서 지우는 것은 다르고,
남아 있어야 나중에 "이 자리 감시" 를 붙일 수 있다.

수집 전인 지점·날짜는 빈 배열이 아니라 **404** 다. "회차가 없는 날" 과 "아직 안 본 날" 은 다른 말이다.

---

## 감시 API — "이 자리가 풀리면 알려줘"

```
POST   /api/watches      { slotId }    감시 등록 (두 번 걸어도 하나)
GET    /api/watches                    내 감시 목록 (지난 날짜는 빠진다)
DELETE /api/watches/{id}               해제
```

**로그인이 필요한 것은 감시 · 기기 등록 · 동호회 API 다.** 조회 API 는 공개다.
감시를 걸려면 **승인된 동호회 멤버**여야 한다 — 아니면 `403 NOT_MEMBER` (아래 "동호회 API").
`Authorization: Bearer <Firebase ID 토큰>` 을 보내면 구글 공개키로 서명을 검증한다 —
**비밀번호가 이 서버를 지나가지 않는다.**

같은 자리에 대한 알림은 **1시간에 한 번**까지다. 평생 1회로 두면 첫 알림을 놓친 사람이
그 자리를 영영 못 받는다 — 취소표는 났다 사라졌다를 반복한다.

발송 경로 기본값은 **로그**다. `notify.channel=fcm` 으로 갈아 끼우면 실제 푸시가 된다 —
~~아직 로그다~~ 운영은 **FCM 이 켜져 있다** (2026-10-07 휴대폰까지 확인 · 2026-10-19 정정).

---

## 동호회 API — 로그인 · 승인 멤버십 (2026-10-19)

```
POST /api/auth/kakao            { code, redirectUri }   카카오 로그인 → { customToken }   (로그인 불필요)
POST /api/auth/kakao/link       { code, redirectUri }   지금 계정에 카카오 연결
GET  /api/me                                            내 상태 (NONE · PENDING · APPROVED · REJECTED · REMOVED)
POST /api/me/join               { nickname, profile? }  가입 신청 → 승인 대기 (profile 은 6단계 · 서버는 선택)
PUT  /api/me/profile            { nickname, profile }   닉네임·프로필 통째로 고치기 (대기 중·승인 멤버)
GET  /api/admin/members?status=                         관리자: 멤버 목록
POST /api/admin/members/{uid}/approve | reject | remove | role
```

**계약 전문(요청·응답·에러 코드·문구)은 Floduler `작업명세서.md` §4.6** 이다. 여기에 두 벌 적지 않는다.

**일정·참여 (2단계)** — 승인 멤버만. 계약 전문은 **§4.7**.

```
GET    /api/club/events?from=&to=        일정 목록 (최대 62일) · 참여자 닉네임까지
GET    /api/club/events/{id}
POST   /api/club/events                  만들기 (만든 사람 자동 참여)
POST   /api/club/events/{id}             수정 — 받아 둔 version 을 같이 보낸다. 낡았으면 409 + 최신 일정
DELETE /api/club/events/{id}             삭제 (표시만)
POST   /api/club/events/{id}/join | leave
POST   /api/club/events/{id}/participants  { uid }      DELETE …/participants/{uid}     대신 넣기·빼기
POST   /api/club/events/{id}/booking       { booked }   예약 상태
GET    /api/club/members                 멤버 목록 — 역할 · 승인일 · 프로필 (6단계) · 대신 넣을 사람 고르기에도
GET    /api/club/rankings?from=&to=      달별 랭킹 YYYY-MM · 최대 12달 · 달마다 5등까지 (6단계)
POST   /api/club/events/{id}/availability  { date, available, startMin, endMin }   후보 날짜에 되는지 (3단계)
DELETE /api/club/events/{id}/availability/{date}                                  응답 지우기
       날짜 미정 일정 = date 가 null · candidates(후보 날짜 1~14개)
GET    /api/club/events/{id}/fresh-themes?date=  그날 열린 테마 중 참여자가 안 해 본 것 (4단계)
GET    /api/me/wishes  · POST { themeKey?, themeName, branchName? } · DELETE /api/me/wishes/{id}   하고 싶은 테마 (7단계)
GET    /api/club/wishes                  같은 테마를 원하는 사람끼리 묶은 목록 (7단계)
GET    /api/club/events/{id}/settlement  · PUT { version, items }    정산 — 송금 계산은 서버 (7단계 · §4.13)
POST   /api/club/events/{id}/settlement/transfers  { fromUid, toUid, paid }   보냈어요 체크
GET    /api/me/payinfo · PUT { bankText, kakaopayUrl }              내 송금 정보
GET    /api/club/notice  · PUT { text, notify } · DELETE           홈 공지 — 하나만 · 쓰기는 운영진 · 없으면 null (§4.14)
GET    /api/club/feedback · POST { text } · PUT|DELETE /{id}          의견 게시판 — 멤버 모두 보기 (§4.15)
POST   /api/club/feedback/{id}/like { liked } · PUT …/{id}/status { status }(운영진)   공감 · 처리 상태
GET|POST /api/club/feedback/{id}/comments · DELETE /api/club/feedback/comments/{commentId}   댓글
GET|POST /api/club/events/{id}/comments · DELETE /api/club/events/comments/{commentId}     일정 댓글 — 알림 감 (§4.16)
GET    /api/me/played                     내 해 본 테마      POST { themeName, themeKey?, … }   DELETE /api/me/played/{id}
GET    /api/club/members/{uid}/played     멤버의 해 본 테마
GET    /api/me/events?before=&beforeId=&limit=  내가 참여한 지난 일정 { events, total } — (date, id) 커서
GET    /api/themes                        해 본 테마 고르기용 테마 목록 (로그인 없이)
POST   /api/me/push-test                  테스트 알림 (로그인만 · 1분에 한 번)
POST   /api/devices/status  { token }     이 기기가 내 알림 기기로 등록돼 있나
       서버가 보내는 동호회 알림: 전날(20~22시 KST) · 마감 · 예약 완료 (5단계) · 새 공지(운영진이 체크할 때만)
```
카카오는 서버가 확인한 뒤 Firebase 커스텀 토큰을 **직접 서명**해 돌려준다 — Admin SDK 없이 ([아키텍처.md](아키텍처.md) D29).
관리자인지는 토큰이 아니라 **DB(`club_member`)** 로 본다 (D28).

---

## 데이터 수집 원칙

기술적으로 가능한 것과 해도 되는 것은 다르다.

- ✅ 매장당 한 번에 하나의 요청, **초당 1회 미만**
- ✅ `429`/`503`(+ `403`) 에는 즉시 백오프 — 그 매장 서버로는 30분부터 두 배씩(최대 4시간) 아무것도 안 보낸다.
  응답이 15초 넘게 안 오면 끊고 그 바퀴의 남은 요청을 접는다 (2026-10-21 · 그 전엔 감시 빠른 확인에만 있었다)
- ✅ `robots.txt` 와 이용약관을 대상 추가 전에 확인
- ❌ **캡차·Cloudflare·WAF 우회는 하지 않는다.** 그건 "오지 마" 라는 명확한 의사표시다
- ❌ **예약 자체를 자동화하지 않는다.** 알림만 보내고 예약은 사람이 한다
- ❌ 브라우저인 척하지 않는다

취소로 비는 자리가 매출로 바뀌는 것이라, 매장과 이해가 대립하지 않는 범위에서만 만든다.

---

## 로컬에서 돌리기

```bash
docker compose up -d                    # Postgres
DEBUG_ENDPOINTS=true ./gradlew bootRun   # 앱 (수집은 기본 꺼짐)

curl 'http://127.0.0.1:8080/debug/collect?date=2026-09-01'   # 한 번 수집
curl 'http://127.0.0.1:8080/api/branches'
```

| 환경변수 | 기본값 | |
|---|---|---|
| `COLLECTOR_ENABLED` | `false` | 주기 수집. **실제로 돌릴 기기에서만 켠다** |
| `DB_POOL_SIZE` | `24` | DB 커넥션 풀. **저장 문(`collector.save-concurrency` 16)보다 커야 한다** — 남는 것이 조회 API 몫이다 |
| `DEBUG_ENDPOINTS` | `false` | 인증 없는 확인용 엔드포인트 |
| `SERVER_ADDRESS` | `127.0.0.1` | 루프백 전용. 남에게 보여줄 때만 `0.0.0.0` |
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | 로컬 compose 기준 | |
| `API_CORS_ORIGINS` | Pages + localhost (운영 compose 는 Pages + Firebase Hosting) | 쉼표로 여러 개. 운영은 `.env` 에 안 적는다 — 적으면 기본값을 통째로 덮는다 |
| `FIREBASE_PROJECT_ID` | `roomescapescheduler` | 감시 API 토큰을 검증할 대상. 비밀값 아님 |
| `NOTIFY_CHANNEL` | `log` | `fcm` 으로 바꾸면 실제 푸시. **서비스 계정 키 파일이 있어야 뜬다** |
| `NOTIFY_COOLDOWN_MINUTES` | `60` | 같은 자리를 다시 알리기까지의 최소 간격 |
| `KAKAO_REST_API_KEY` | (없음) | 카카오 로그인(동호회). 비어 있으면 카카오 로그인만 `503`. 비밀값 아님 |
| `KAKAO_CLIENT_SECRET` | (없음) | REST API 키의 클라이언트 시크릿(카카오 로그인용). 개편 후 콘솔은 기본으로 켜 두어 **사실상 필수**. **비밀값** |
| `KAKAO_ALLOWED_REDIRECT_URIS` | Pages + Firebase Hosting + localhost | 카카오가 돌려보낼 수 있는 화면 주소. 콘솔 값과 글자 그대로 같게 |
| `CLUB_BOOTSTRAP_ADMIN_UID` | (없음) | 첫 동호회 관리자 uid. **관리자가 0명일 때만** 쓰인다. 빈자리 알림이 승인 멤버로 묶여 있어 배포 전에 넣는다 (배포.md "⑬") |

```bash
./gradlew test    # 거의 다 DB 없이 돈다 (H2 인메모리)
```

**하나만 Docker 가 필요하다** — `MigrationValidationTest` 가 진짜 Postgres(17)를 띄워 마이그레이션 V1~ 을 돌리고
엔티티와 맞춰 본다. H2 는 마이그레이션 SQL 을 안 돌리므로 **운영에서만 터지는 스키마 불일치**를 잡을 곳이 여기뿐이다.
Docker 가 없으면 실패가 아니라 **건너뛴다**(그러니 "다 통과" 를 믿기 전에 건너뛴 수를 본다).

```bash
colima start      # 이 맥은 colima 다. Testcontainers 는 docker context 를 안 따라가서 소켓을 직접 알려 준다
DOCKER_HOST=unix://$HOME/.colima/default/docker.sock \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock ./gradlew test
```

---

## 배포

**절차 전체는 [배포.md](배포.md)** — 오라클 계정 만들기부터 Floduler 연결까지 순서대로.

갓 만든 우분투 서버 하나를 한 줄로 올린다. 여러 번 실행해도 안전하다.
코드를 고친 뒤 다시 배포할 때도 **같은 줄을 그대로** 돌리면 된다.

```bash
ssh <서버> 'bash -s' -- <도메인> [DuckDNS토큰] < deploy/bootstrap.sh
```

도커 설치 → 방화벽 → **서버 잠그기** → DuckDNS → 코드 → `.env` 생성 → 빌드·기동 → 응답 확인.

**서버 잠그기**는 셋이다. 인터넷에 붙은 기계는 몇 분 안에 스캔당한다.

| | |
|---|---|
| SSH 비밀번호·root 로그인 차단 | **키가 있는 걸 확인하고 나서만** 끈다. 확인 없이 끄면 자기 자신을 영영 잠근다 |
| 자동 보안 업데이트 | 커널 패치를 위해 필요할 때만 04:00 재부팅. 컨테이너는 알아서 돌아온다 |
| fail2ban | SSH 5회 실패 → 1시간 차단 |

SSH·fail2ban 설정은 **적용 전에 검사**하고, 검사에 실패하면 되돌린다 —
잘못된 설정으로 재시작해서 서버에 못 들어가는 것보다 안 잠긴 채로 두는 편이 낫다.

> ⚠️ **클라우드 쪽 방화벽은 콘솔에서 따로 열어야 한다.**
> 오라클은 방화벽이 두 겹(VCN 보안 목록 + 서버 iptables)이라, 안쪽만 열고 "왜 접속이 안 되지" 로 헤매기 쉽다.
> **80 과 443 을 둘 다** 열 것 — HTTPS 인증서는 80 이 열려야 발급된다.

구성은 `docker-compose.prod.yml` (db + app + caddy). DB 포트는 호스트에 열지 않고,
Caddy 가 `/api` 만 프록시하며 나머지는 404 다. 스키마는 Flyway 가 만든다.

---

## 문서

| | |
|---|---|
| [기획.md](기획.md) | 무엇을 왜 만드나. 수집 원칙, 조사 결과, 마일스톤 |
| [아키텍처.md](아키텍처.md) | 어떻게 생겼나. **설계 결정 D1~D9 를 이유까지**, 서버가 처음인 사람을 위한 용어집 |
| [작업명세서.md](작업명세서.md) | 어디까지 했고 다음은 뭔가 |

코드만 바꾸고 문서를 두면 문서가 거짓말을 시작한다. **같은 커밋에서 함께 고친다.**
