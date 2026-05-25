# Step 3 — Spring Boot Skeleton Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Boot a minimal Spring Boot 3.3 app in `api/` connected to Cassandra, Redis, and RabbitMQ, with Actuator, `/healthz`, and idempotent counter seeding.

**Architecture:** Pure plumbing — no business logic. `build.gradle.kts` replaces the stub with Spring Boot 3.3 + Java 21 toolchain. Three source files: main class, `/healthz` controller, Redis initializer. `application.yml` uses Docker Compose service names by default (`docker` profile) and overrides to `localhost` for the `local` profile used during local `bootRun`. Gradle Wrapper is bootstrapped via Docker (no global `gradle` installed).

**Tech Stack:** Spring Boot 3.3.4, Java 21 (Eclipse Temurin), Gradle 8.8 (Kotlin DSL), Spring Data Cassandra, Spring Data Redis (Lettuce), Spring AMQP, Spring Boot Actuator, Micrometer Prometheus

---

## File Map

| File | Action | Purpose |
|---|---|---|
| `api/gradlew`, `api/gradlew.bat` | Create (Docker bootstrap) | Gradle Wrapper scripts |
| `api/gradle/wrapper/gradle-wrapper.properties` | Create (Docker bootstrap) | Gradle Wrapper config |
| `api/gradle/wrapper/gradle-wrapper.jar` | Create (Docker bootstrap) | Gradle Wrapper binary |
| `api/build.gradle.kts` | Modify | Full Spring Boot 3.3 build |
| `api/src/main/java/com/shortener/UrlShortenerApplication.java` | Create | `@SpringBootApplication` main class |
| `api/src/main/resources/application.yml` | Create | Base + `local` + `docker` profile config |
| `api/src/main/java/com/shortener/HealthzController.java` | Create | `GET /healthz` endpoint |
| `api/src/main/java/com/shortener/RedisInitializer.java` | Create | Seeds `url:counter` on startup |

---

### Task 1: Bootstrap Gradle Wrapper

**Files:**
- Create: `api/gradlew`, `api/gradlew.bat`, `api/gradle/wrapper/gradle-wrapper.properties`, `api/gradle/wrapper/gradle-wrapper.jar`

- [ ] **Step 1: Bootstrap wrapper via Docker**

Run from the repo root (not inside `api/`):

```bash
docker run --rm -v "$(pwd)/api":/project -w /project gradle:8.8-jdk21 gradle wrapper --gradle-version 8.8
```

Expected: no error output. Creates `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.properties`, `gradle/wrapper/gradle-wrapper.jar` inside `api/`.

- [ ] **Step 2: Make gradlew executable**

```bash
chmod +x api/gradlew
```

- [ ] **Step 3: Verify wrapper files exist**

```bash
ls api/gradlew api/gradlew.bat api/gradle/wrapper/gradle-wrapper.properties api/gradle/wrapper/gradle-wrapper.jar
```

Expected: all four paths print without error.

- [ ] **Step 4: Verify wrapper version**

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 api/gradlew --version
```

Expected output contains `Gradle 8.8` and `JVM: 21`.

---

### Task 2: Write build.gradle.kts

**Files:**
- Modify: `api/build.gradle.kts`

- [ ] **Step 1: Replace stub with full build file**

Write this exact content to `api/build.gradle.kts`:

```kotlin
plugins {
    java
    id("org.springframework.boot") version "3.3.4"
    id("io.spring.dependency-management") version "1.1.6"
}

group = "com.shortener"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-cassandra")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-amqp")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
```

- [ ] **Step 2: Verify dependencies resolve**

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 api/gradlew -p api dependencies --configuration compileClasspath --quiet 2>&1 | tail -5
```

Expected: ends with `BUILD SUCCESSFUL` (no resolution errors). This downloads ~150 MB of jars on first run — wait up to 3 minutes.

---

### Task 3: Create package directory and main class

**Files:**
- Create: `api/src/main/java/com/shortener/UrlShortenerApplication.java`

- [ ] **Step 1: Create package directory**

```bash
mkdir -p api/src/main/java/com/shortener
```

- [ ] **Step 2: Remove .gitkeep from java source dir (no longer needed)**

```bash
rm api/src/main/java/.gitkeep
```

- [ ] **Step 3: Write UrlShortenerApplication.java**

```java
package com.shortener;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class UrlShortenerApplication {

    public static void main(String[] args) {
        SpringApplication.run(UrlShortenerApplication.class, args);
    }
}
```

---

### Task 4: Create application.yml

**Files:**
- Create: `api/src/main/resources/application.yml`

- [ ] **Step 1: Create resources directory**

```bash
mkdir -p api/src/main/resources
```

- [ ] **Step 2: Write application.yml**

```yaml
spring:
  profiles:
    active: ${SPRING_PROFILES_ACTIVE:docker}
  cassandra:
    contact-points: cassandra
    port: 9042
    keyspace-name: shortener
    local-datacenter: datacenter1
    schema-action: none
  data:
    redis:
      host: redis
      port: 6379
  rabbitmq:
    host: rabbitmq
    port: 5672
    username: guest
    password: guest

shortener:
  secret-key: ${SHORTENER_SECRET_KEY:change-me-in-prod}
  base-url: ${BASE_URL:http://localhost}
  cache-ttl-seconds: 604800
  negcache-ttl-seconds: 60

management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  metrics:
    tags:
      application: url-shortener
      instance: ${HOSTNAME:unknown}

---
spring:
  config:
    activate:
      on-profile: local
  cassandra:
    contact-points: localhost
  data:
    redis:
      host: localhost
  rabbitmq:
    host: localhost
```

- [ ] **Step 3: Verify file contains both profile sections**

```bash
grep -c "on-profile\|contact-points\|SPRING_PROFILES_ACTIVE" api/src/main/resources/application.yml
```

Expected: `3` (three matching lines).

---

### Task 5: Create HealthzController

**Files:**
- Create: `api/src/main/java/com/shortener/HealthzController.java`

- [ ] **Step 1: Write HealthzController.java**

```java
package com.shortener;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HealthzController {

    @GetMapping("/healthz")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }
}
```

---

### Task 6: Create RedisInitializer

**Files:**
- Create: `api/src/main/java/com/shortener/RedisInitializer.java`

- [ ] **Step 1: Write RedisInitializer.java**

```java
package com.shortener;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisInitializer {

    private final StringRedisTemplate redisTemplate;

    public RedisInitializer(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initializeCounter() {
        redisTemplate.opsForValue().setIfAbsent("url:counter", "250000");
    }
}
```

---

### Task 7: Compile verification

**Files:** (no new files)

- [ ] **Step 1: Compile main sources**

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 api/gradlew -p api compileJava
```

Expected: `BUILD SUCCESSFUL`. Zero compilation errors.

---

### Task 8: Runtime verification

**Files:** (no new files)

- [ ] **Step 1: Ensure Docker Compose infra is running**

```bash
docker compose ps --format '{{.Name}} {{.Health}}' 2>/dev/null | grep -E 'cassandra|redis|rabbitmq'
```

Expected: three lines showing `healthy`. If any are missing or not healthy, start them:

```bash
docker compose up -d cassandra redis rabbitmq
```

Then wait up to 90s for Cassandra to become healthy:

```bash
for i in $(seq 1 30); do
  status=$(docker compose ps --format '{{.Health}}' cassandra 2>/dev/null)
  [ "$status" = "healthy" ] && echo "Cassandra healthy" && break
  echo "Attempt $i/30 — cassandra: $status"; sleep 3
done
```

- [ ] **Step 2: Build the runnable jar**

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 api/gradlew -p api build -x test
```

Expected: `BUILD SUCCESSFUL`. Jar at `api/build/libs/url-shortener-0.0.1-SNAPSHOT.jar`.

- [ ] **Step 3: Start the app in the background**

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 java \
  -jar api/build/libs/url-shortener-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=local \
  > /tmp/spring-step3.log 2>&1 &
echo $! > /tmp/spring-step3.pid
echo "Started PID $(cat /tmp/spring-step3.pid)"
```

- [ ] **Step 4: Wait for startup (up to 60s)**

```bash
for i in $(seq 1 30); do
  curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/actuator/health 2>/dev/null | grep -q "200" && echo "App started!" && break
  echo "Attempt $i/30 — waiting..."; sleep 2
done
```

Expected final output: `App started!`

If it never starts, check the log:
```bash
tail -50 /tmp/spring-step3.log
```

- [ ] **Step 5: Verify /healthz**

```bash
curl -s http://localhost:8080/healthz
```

Expected: `{"status":"ok"}`

- [ ] **Step 6: Verify Actuator Prometheus endpoint**

```bash
curl -s http://localhost:8080/actuator/prometheus | head -20
```

Expected: lines starting with `#` (Prometheus comment/help lines) followed by metric names like `jvm_memory_used_bytes` etc.

- [ ] **Step 7: Verify Redis counter is set**

```bash
docker compose exec redis redis-cli GET url:counter
```

Expected: `"250000"`

- [ ] **Step 8: Verify counter is NOT reset on second startup**

```bash
docker compose exec redis redis-cli SET url:counter 999999
```

Kill and restart the app:

```bash
kill $(cat /tmp/spring-step3.pid)
sleep 3
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 java \
  -jar api/build/libs/url-shortener-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=local \
  > /tmp/spring-step3.log 2>&1 &
echo $! > /tmp/spring-step3.pid
```

Wait for startup:

```bash
for i in $(seq 1 30); do
  curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/actuator/health 2>/dev/null | grep -q "200" && echo "App restarted!" && break
  echo "Attempt $i/30 — waiting..."; sleep 2
done
```

Then check the counter:

```bash
docker compose exec redis redis-cli GET url:counter
```

Expected: still `"999999"` — the `setIfAbsent` (NX) did not overwrite it.

Reset it back to the correct value:

```bash
docker compose exec redis redis-cli SET url:counter 250000
```

- [ ] **Step 9: Stop the background app**

```bash
kill $(cat /tmp/spring-step3.pid) 2>/dev/null; rm -f /tmp/spring-step3.pid /tmp/spring-step3.log
```

---

### Task 9: Commit

- [ ] **Step 1: Stage all new and modified files**

```bash
git add \
  api/build.gradle.kts \
  api/gradlew \
  api/gradlew.bat \
  api/gradle/wrapper/gradle-wrapper.properties \
  api/gradle/wrapper/gradle-wrapper.jar \
  api/src/main/java/com/shortener/UrlShortenerApplication.java \
  api/src/main/resources/application.yml \
  api/src/main/java/com/shortener/HealthzController.java \
  api/src/main/java/com/shortener/RedisInitializer.java
```

Also stage the removed .gitkeep:

```bash
git add api/src/main/java/.gitkeep
```

- [ ] **Step 2: Verify staged files**

```bash
git status
```

Expected: the files above appear under "Changes to be committed". No build output, no `.gradle/` dir, no `.env`.

- [ ] **Step 3: Commit**

```bash
git commit -m "feat(step-3): Spring Boot skeleton"
```

- [ ] **Step 4: Verify**

```bash
git log --oneline | head -3
```

Expected: top commit is `feat(step-3): Spring Boot skeleton`.
