# ── 빌드 ──────────────────────────────────────────────
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src

# 의존성만 먼저 받아 레이어로 굳힌다. src 만 바뀌면 이 단계는 캐시가 그대로 쓰인다
COPY gradle gradle
COPY gradlew settings.gradle.kts build.gradle.kts ./
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies

COPY src src
# 테스트는 CI/로컬에서 이미 돌렸다. 이미지 빌드에서 또 돌리면 배포만 느려진다
RUN ./gradlew --no-daemon bootJar -x test

# ── 실행 ──────────────────────────────────────────────
FROM eclipse-temurin:21-jre
WORKDIR /app

# root 로 돌릴 이유가 없다
RUN useradd --system --uid 10001 app
USER app

COPY --from=build /src/build/libs/*.jar app.jar

# ⚠️ 앱 기본값은 루프백 바인딩(127.0.0.1)이다. 컨테이너 안에서 그대로 두면
#    같은 도커 네트워크의 Caddy 도 못 붙는다. 컨테이너 안은 이미 격리돼 있고
#    바깥으로는 Caddy 만 포트를 여니 여기서는 0.0.0.0 이 맞다.
ENV SERVER_ADDRESS=0.0.0.0

EXPOSE 8080
# **중간 인증서를 안 보내는 매장이 있다.** 플레이포인트랩(2026-09-08)이 리프
# 인증서만 보내서 JDK 가 체인을 못 잇고 `PKIX path building failed` 로 12요청이
# 통째로 실패했다. 루트(Sectigo … Root R46)는 신뢰 저장소에 **있다** — 빠진 것은
# 그 사이 중간 인증서(R36)뿐이고, 브라우저와 curl 은 인증서의 AIA(`CA Issuers` URI)를
# 보고 그걸 스스로 받아온다. 자바는 그 동작이 기본으로 꺼져 있다.
#
# **플래그가 둘 다 필요하다.** `enableAIAcaIssuers` 만 켜면 안 된다 —
# 최신 JDK 는 `allowedAIALocations` 로 AIA 주소를 거르는데 **기본이 전부 거부**라
# 조용히 막힌다(`java.security.debug=certpath` 로 보면
# "No valid filters found. Deny-all URI filtering is active." 가 찍힌다).
# 하나만 켜고 배포했다가 한 바퀴를 더 버릴 뻔했다 — 서버에서 직접 재 보고 알았다.
#
# **필요한 호스트만 연다.** 아무 데나 열면 AIA URI 를 따라 임의의 주소로 나가게 된다.
#
# ⚠️ **검증을 끄는 게 아니다.** 체인을 *완성*하는 것뿐이고, 완성된 체인은 여전히
# 신뢰 저장소의 루트까지 닿아야 통과한다. 신뢰를 새로 만들어 내지 않는다.
# 중간 인증서를 truststore 에 손으로 박는 대안은 **갱신될 때 조용히 깨진다** —
# 그때는 로그에 같은 PKIX 오류만 남고 원인이 안 보인다.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", \
            "-Dcom.sun.security.enableAIAcaIssuers=true", \
            "-Dcom.sun.security.allowedAIALocations=http://crt.sectigo.com", \
            "-jar", "/app/app.jar"]
