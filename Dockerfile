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
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
