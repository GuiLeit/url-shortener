# Step 3 — Spring Boot Skeleton Design

**Date:** 2026-05-24
**Scope:** PRD Step 3 — bootable Spring Boot 3.3 app skeleton in `api/` with no business logic yet.

---

## Goal

A Spring Boot 3.3 application in `api/` that:
- Connects to Cassandra, Redis, and RabbitMQ (service names when `docker` profile; localhost when `local`)
- Exposes Actuator endpoints: `health`, `info`, `metrics`, `prometheus`
- Exposes `GET /healthz` returning `{"status":"ok"}`
- Seeds `url:counter` to 250000 in Redis on startup via `SET NX` (idempotent)

No business logic. The Docker Compose `api` service is added in Step 9.

---

## Files Modified / Created

| File | Action |
|---|---|
| `api/build.gradle.kts` | Replace stub — full Spring Boot 3.3 Kotlin DSL build |
| `api/src/main/java/com/shortener/UrlShortenerApplication.java` | Create — `@SpringBootApplication` main class |
| `api/src/main/resources/application.yml` | Create — base + `local` + `docker` profile sections |
| `api/src/main/java/com/shortener/HealthzController.java` | Create — `GET /healthz` |
| `api/src/main/java/com/shortener/RedisInitializer.java` | Create — seeds counter on `ApplicationReadyEvent` |
| `api/gradle/wrapper/` + `gradlew` + `gradlew.bat` | Bootstrap via `gradle:8.8-jdk21` Docker container |

---

## Gradle Build

**`api/build.gradle.kts`:**
- Plugins: `java`, `org.springframework.boot` (3.3.x latest patch), `io.spring.dependency-management`
- `java.toolchain.languageVersion = JavaLanguageVersion.of(21)`
- Dependencies:
  - `spring-boot-starter-web`
  - `spring-boot-starter-data-cassandra`
  - `spring-boot-starter-data-redis`
  - `spring-boot-starter-amqp`
  - `spring-boot-starter-actuator`
  - `micrometer-registry-prometheus`
  - `spring-boot-starter-validation`
  - `spring-boot-starter-test` (testImplementation)

**`api/settings.gradle.kts`:** Already has `rootProject.name = "url-shortener"` — no change.

---

## application.yml

Single file at `api/src/main/resources/application.yml` with three sections separated by `---`.

**Base section (active for all profiles):**
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
```

**`local` profile override:**
```yaml
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

**`docker` profile override:** No additional overrides needed — base config already uses service names.

---

## Package Structure

Base package: `com.shortener` (established by Step 4's `com.shortener.encoding.Base62Encoder`).

```
api/src/main/java/com/shortener/
├── UrlShortenerApplication.java
├── HealthzController.java
└── RedisInitializer.java
```

---

## Key Classes

### UrlShortenerApplication
```java
@SpringBootApplication
public class UrlShortenerApplication {
    public static void main(String[] args) {
        SpringApplication.run(UrlShortenerApplication.class, args);
    }
}
```

### HealthzController
```java
@RestController
public class HealthzController {
    @GetMapping("/healthz")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }
}
```

### RedisInitializer
```java
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

## Gradle Wrapper Bootstrap

No global `gradle` is installed. Bootstrap using Docker:

```bash
cd api
docker run --rm -v "$(pwd)":/project -w /project gradle:8.8-jdk21 gradle wrapper --gradle-version 8.8
```

Commits `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.properties`, `gradle/wrapper/gradle-wrapper.jar`.

---

## Verification

```bash
cd api
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew bootRun --args='--spring.profiles.active=local'
curl http://localhost:8080/healthz
# Expected: {"status":"ok"}
curl http://localhost:8080/actuator/prometheus | head -20
# Expected: Prometheus metrics output
```

---

## Definition of Done (from PRD)

- App boots with `./gradlew bootRun --args='--spring.profiles.active=local'`
- `/healthz` returns `{"status":"ok"}`
- `/actuator/prometheus` returns metrics
- Redis `url:counter` equals `250000` after first boot
- Subsequent boots do NOT reset the counter
