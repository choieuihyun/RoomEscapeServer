plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.spring") version "2.3.21"
    kotlin("plugin.jpa") version "2.3.21"
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.my_dream"
version = "0.0.1-SNAPSHOT"
description = "Server"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("org.jsoup:jsoup:1.21.1")
    // Spring Boot 4 는 Flyway 자동설정이 starter 로 분리돼 있다 (flyway-core 만으로는 안 돈다)
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    // 알림 신청은 "누가" 를 알아야 한다. Floduler 가 이미 쓰는 Firebase Auth 의
    // ID 토큰을 검증한다 — 비밀번호를 우리가 다루지 않는 쪽이 항상 안전하다 (D12)
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    // FCM 발송용 액세스 토큰만 여기서 얻는다. firebase-admin 이 아니라 이 작은 라이브러리를 쓰는 이유는
    // 아키텍처 D16 — admin 은 gRPC·netty·Firestore 까지 끌고 오는데 우리가 쓰는 건 POST 하나다
    implementation("com.google.auth:google-auth-library-oauth2-http:1.51.0")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    developmentOnly("org.springframework.boot:spring-boot-devtools")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    // Spring Boot 4 는 테스트 슬라이스가 모듈별로 쪼개져 있다. @DataJpaTest 는 여기 들어 있다
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    // 컨텍스트 로딩 스모크 테스트 전용. 실제 저장 동작은 Postgres 로 확인한다
    testRuntimeOnly("com.h2database:h2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
