# Step 5 — URL Creation Endpoint Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement `POST /api/v1/urls` that atomically increments a Redis counter, encodes to a Base62 shortcode, persists to Cassandra, pre-warms Redis cache, and returns HTTP 201 with the shortcode.

**Architecture:** Three packages added: `com.shortener.url` (controller, service, repository, entity, DTOs), `com.shortener.web` (exception handler, error DTO), `com.shortener.validation` (custom `@NonRecursiveUrl` constraint). TDD order: failing service test first, then supporting types, then service implementation, then controller.

**Tech Stack:** Spring MVC, Spring Data Cassandra (`CassandraRepository`), Spring Data Redis (`StringRedisTemplate`), Hibernate Validator (`@URL`), JSR-380, Mockito

---

## File Map

| File | Action |
|---|---|
| `api/src/test/java/com/shortener/url/UrlServiceTest.java` | Create — failing first |
| `api/src/main/java/com/shortener/url/CreateUrlRequest.java` | Create — request DTO with validation |
| `api/src/main/java/com/shortener/url/CreateUrlResponse.java` | Create — response DTO |
| `api/src/main/java/com/shortener/web/ErrorResponse.java` | Create — error body DTO |
| `api/src/main/java/com/shortener/validation/NonRecursiveUrl.java` | Create — constraint annotation |
| `api/src/main/java/com/shortener/validation/NonRecursiveUrlValidator.java` | Create — constraint validator |
| `api/src/main/java/com/shortener/url/Url.java` | Create — Cassandra entity |
| `api/src/main/java/com/shortener/url/UrlRepository.java` | Create — Spring Data Cassandra repo |
| `api/src/main/java/com/shortener/url/UrlService.java` | Create — business logic |
| `api/src/main/java/com/shortener/url/UrlController.java` | Create — REST controller |
| `api/src/main/java/com/shortener/web/GlobalExceptionHandler.java` | Create — `@RestControllerAdvice` |
| `api/src/main/resources/application.yml` | Modify — add Jackson snake_case config |

---

### Task 1: Write failing UrlServiceTest

**Files:**
- Create: `api/src/test/java/com/shortener/url/UrlServiceTest.java`

- [ ] **Step 1: Create test package directory**

```bash
mkdir -p api/src/test/java/com/shortener/url
```

- [ ] **Step 2: Write UrlServiceTest.java**

```java
package com.shortener.url;

import com.shortener.encoding.Base62Encoder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UrlServiceTest {

    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOps;
    @Mock UrlRepository urlRepository;
    @Mock Base62Encoder encoder;

    UrlService service;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        service = new UrlService(redisTemplate, urlRepository, encoder, "http://localhost");
    }

    @Test
    void create_incrementsCounter_encodesId_savesAndCaches() {
        when(valueOps.increment("url:counter")).thenReturn(250000L);
        when(encoder.encode(250000L)).thenReturn("Hk2p");

        CreateUrlResponse response = service.create("https://example.com/path");

        verify(valueOps).increment("url:counter");
        verify(encoder).encode(250000L);
        verify(urlRepository).save(argThat(url ->
            url.getShortCode().equals("Hk2p") &&
            url.getLongUrl().equals("https://example.com/path") &&
            url.getUrlId().equals(250000L)
        ));
        verify(valueOps).set(
            eq("url:cache:Hk2p"),
            eq("https://example.com/path"),
            eq(Duration.ofSeconds(604800))
        );
        assertEquals("Hk2p", response.getShortCode());
        assertEquals("http://localhost/Hk2p", response.getShortUrl());
        assertEquals("https://example.com/path", response.getLongUrl());
        assertNotNull(response.getCreatedAt());
    }
}
```

- [ ] **Step 3: Run — verify it FAILS (compile error)**

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 api/gradlew -p api test --tests "com.shortener.url.UrlServiceTest" 2>&1 | tail -10
```

Expected: `BUILD FAILED` — `cannot find symbol` for `UrlService`, `UrlRepository`, `CreateUrlResponse`. This is correct TDD red phase.

---

### Task 2: Create DTOs and error response

**Files:**
- Create: `api/src/main/java/com/shortener/url/CreateUrlRequest.java`
- Create: `api/src/main/java/com/shortener/url/CreateUrlResponse.java`
- Create: `api/src/main/java/com/shortener/web/ErrorResponse.java`

- [ ] **Step 1: Create package directories**

```bash
mkdir -p api/src/main/java/com/shortener/url
mkdir -p api/src/main/java/com/shortener/web
```

- [ ] **Step 2: Write CreateUrlRequest.java**

```java
package com.shortener.url;

import com.shortener.validation.NonRecursiveUrl;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.URL;

public class CreateUrlRequest {

    @NotBlank
    @URL
    @Size(max = 2048)
    @NonRecursiveUrl
    private String url;

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
}
```

- [ ] **Step 3: Write CreateUrlResponse.java**

```java
package com.shortener.url;

import java.time.Instant;

public class CreateUrlResponse {

    private final String shortCode;
    private final String shortUrl;
    private final String longUrl;
    private final Instant createdAt;

    public CreateUrlResponse(String shortCode, String shortUrl, String longUrl, Instant createdAt) {
        this.shortCode = shortCode;
        this.shortUrl = shortUrl;
        this.longUrl = longUrl;
        this.createdAt = createdAt;
    }

    public String getShortCode() { return shortCode; }
    public String getShortUrl() { return shortUrl; }
    public String getLongUrl() { return longUrl; }
    public Instant getCreatedAt() { return createdAt; }
}
```

- [ ] **Step 4: Write ErrorResponse.java**

```java
package com.shortener.web;

import java.util.List;

public class ErrorResponse {

    private final String message;
    private final List<String> errors;

    public ErrorResponse(String message, List<String> errors) {
        this.message = message;
        this.errors = errors;
    }

    public String getMessage() { return message; }
    public List<String> getErrors() { return errors; }
}
```

---

### Task 3: Create validation annotation and validator

**Files:**
- Create: `api/src/main/java/com/shortener/validation/NonRecursiveUrl.java`
- Create: `api/src/main/java/com/shortener/validation/NonRecursiveUrlValidator.java`

- [ ] **Step 1: Create validation package**

```bash
mkdir -p api/src/main/java/com/shortener/validation
```

- [ ] **Step 2: Write NonRecursiveUrl.java**

```java
package com.shortener.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

@Documented
@Constraint(validatedBy = NonRecursiveUrlValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface NonRecursiveUrl {
    String message() default "URL must not point to this shortener service";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
```

- [ ] **Step 3: Write NonRecursiveUrlValidator.java**

```java
package com.shortener.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;

@Component
public class NonRecursiveUrlValidator implements ConstraintValidator<NonRecursiveUrl, String> {

    @Value("${shortener.base-url:http://localhost}")
    private String baseUrl;

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) return true;
        try {
            String inputHost = URI.create(value).getHost();
            String shortenerHost = URI.create(baseUrl).getHost();
            return !inputHost.equalsIgnoreCase(shortenerHost);
        } catch (Exception e) {
            return false;
        }
    }
}
```

---

### Task 4: Create Cassandra entity and repository

**Files:**
- Create: `api/src/main/java/com/shortener/url/Url.java`
- Create: `api/src/main/java/com/shortener/url/UrlRepository.java`

- [ ] **Step 1: Write Url.java**

```java
package com.shortener.url;

import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKey;
import org.springframework.data.cassandra.core.mapping.Table;

import java.time.Instant;

@Table("urls_by_shortcode")
public class Url {

    @PrimaryKey("short_code")
    private String shortCode;

    @Column("long_url")
    private String longUrl;

    @Column("url_id")
    private Long urlId;

    @Column("created_at")
    private Instant createdAt;

    public Url() {}

    public Url(String shortCode, String longUrl, Long urlId, Instant createdAt) {
        this.shortCode = shortCode;
        this.longUrl = longUrl;
        this.urlId = urlId;
        this.createdAt = createdAt;
    }

    public String getShortCode() { return shortCode; }
    public String getLongUrl() { return longUrl; }
    public Long getUrlId() { return urlId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setShortCode(String shortCode) { this.shortCode = shortCode; }
    public void setLongUrl(String longUrl) { this.longUrl = longUrl; }
    public void setUrlId(Long urlId) { this.urlId = urlId; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
```

- [ ] **Step 2: Write UrlRepository.java**

```java
package com.shortener.url;

import org.springframework.data.cassandra.repository.CassandraRepository;

public interface UrlRepository extends CassandraRepository<Url, String> {}
```

---

### Task 5: Implement UrlService — verify test passes

**Files:**
- Create: `api/src/main/java/com/shortener/url/UrlService.java`

- [ ] **Step 1: Write UrlService.java**

```java
package com.shortener.url;

import com.shortener.encoding.Base62Encoder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

@Service
public class UrlService {

    private final StringRedisTemplate redisTemplate;
    private final UrlRepository urlRepository;
    private final Base62Encoder encoder;
    private final String baseUrl;

    public UrlService(StringRedisTemplate redisTemplate,
                      UrlRepository urlRepository,
                      Base62Encoder encoder,
                      @Value("${shortener.base-url}") String baseUrl) {
        this.redisTemplate = redisTemplate;
        this.urlRepository = urlRepository;
        this.encoder = encoder;
        this.baseUrl = baseUrl;
    }

    public CreateUrlResponse create(String longUrl) {
        long id = redisTemplate.opsForValue().increment("url:counter");
        String code = encoder.encode(id);
        Instant now = Instant.now();
        urlRepository.save(new Url(code, longUrl, id, now));
        redisTemplate.opsForValue().set("url:cache:" + code, longUrl, Duration.ofSeconds(604800));
        return new CreateUrlResponse(code, baseUrl + "/" + code, longUrl, now);
    }
}
```

- [ ] **Step 2: Run UrlServiceTest — verify it PASSES**

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 api/gradlew -p api test --tests "com.shortener.url.UrlServiceTest" 2>&1 | tail -8
```

Expected: `BUILD SUCCESSFUL`, `1 test completed, 0 failed`.

---

### Task 6: Create UrlController and GlobalExceptionHandler

**Files:**
- Create: `api/src/main/java/com/shortener/url/UrlController.java`
- Create: `api/src/main/java/com/shortener/web/GlobalExceptionHandler.java`

- [ ] **Step 1: Write UrlController.java**

```java
package com.shortener.url;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/urls")
public class UrlController {

    private final UrlService urlService;

    public UrlController(UrlService urlService) {
        this.urlService = urlService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreateUrlResponse create(@Valid @RequestBody CreateUrlRequest req) {
        return urlService.create(req.getUrl());
    }
}
```

- [ ] **Step 2: Write GlobalExceptionHandler.java**

```java
package com.shortener.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleValidation(MethodArgumentNotValidException ex) {
        List<String> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.toList());
        return new ErrorResponse("Validation failed", errors);
    }
}
```

- [ ] **Step 3: Run full test suite**

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 api/gradlew -p api test 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`.

---

### Task 7: Update application.yml with Jackson config

**Files:**
- Modify: `api/src/main/resources/application.yml`

- [ ] **Step 1: Add Jackson snake_case config to the base section**

Open `api/src/main/resources/application.yml` and add the following block immediately after the `spring:` key's existing entries, before the `---` separator. The result should look like:

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
  jackson:
    property-naming-strategy: SNAKE_CASE

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

- [ ] **Step 2: Verify**

```bash
grep "SNAKE_CASE" api/src/main/resources/application.yml
```

Expected: `    property-naming-strategy: SNAKE_CASE`

---

### Task 8: Runtime verification

- [ ] **Step 1: Ensure Docker Compose infra is running**

```bash
docker compose ps --format '{{.Name}} {{.Health}}' | grep -E 'cassandra|redis|rabbitmq'
```

If any are missing, start them:

```bash
docker compose up -d cassandra redis rabbitmq
for i in $(seq 1 30); do
  status=$(docker compose ps --format '{{.Health}}' cassandra 2>/dev/null)
  [ "$status" = "healthy" ] && echo "Cassandra healthy" && break
  echo "Attempt $i/30 — $status"; sleep 3
done
```

- [ ] **Step 2: Build the jar**

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 api/gradlew -p api build -x test
```

Expected: `BUILD SUCCESSFUL`. Jar at `api/build/libs/url-shortener-0.0.1-SNAPSHOT.jar`.

- [ ] **Step 3: Start app in background**

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 java \
  -jar api/build/libs/url-shortener-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=local \
  > /tmp/spring-step5.log 2>&1 &
echo $! > /tmp/spring-step5.pid
```

- [ ] **Step 4: Wait for startup**

```bash
for i in $(seq 1 30); do
  curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/actuator/health 2>/dev/null | grep -q "200" && echo "App started!" && break
  echo "Attempt $i/30 — waiting..."; sleep 2
done
```

Expected: `App started!`. If it fails, check: `tail -50 /tmp/spring-step5.log`

- [ ] **Step 5: Create a short URL**

```bash
curl -s -X POST http://localhost:8080/api/v1/urls \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com/very/long/path?with=params"}' | python3 -m json.tool
```

Expected: HTTP 201 with JSON like:
```json
{
    "short_code": "Hk2p",
    "short_url": "http://localhost/Hk2p",
    "long_url": "https://example.com/very/long/path?with=params",
    "created_at": "2026-05-25T..."
}
```

The `short_code` must be exactly 4 characters (since counter starts at 250000+1).

Save the short code:
```bash
SHORT_CODE=$(curl -s -X POST http://localhost:8080/api/v1/urls \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com/another"}' | python3 -c "import sys,json; print(json.load(sys.stdin)['short_code'])")
echo "SHORT_CODE=$SHORT_CODE"
```

- [ ] **Step 6: Verify row in Cassandra**

```bash
docker compose exec cassandra cqlsh -e "SELECT short_code, long_url, url_id FROM shortener.urls_by_shortcode LIMIT 5;"
```

Expected: rows present with the shortcodes just created.

- [ ] **Step 7: Verify Redis cache**

```bash
docker compose exec redis redis-cli GET "url:cache:$SHORT_CODE"
```

Expected: `"https://example.com/another"`

- [ ] **Step 8: Verify invalid URL rejected**

```bash
curl -s -X POST http://localhost:8080/api/v1/urls \
  -H 'Content-Type: application/json' \
  -d '{"url":"not-a-url"}' | python3 -m json.tool
```

Expected: HTTP 400 with JSON containing `"message": "Validation failed"` and `"errors"` list.

- [ ] **Step 9: Stop app**

```bash
kill $(cat /tmp/spring-step5.pid) 2>/dev/null; rm -f /tmp/spring-step5.pid /tmp/spring-step5.log
```

---

### Task 9: Commit

- [ ] **Step 1: Stage all files**

```bash
git add \
  api/src/test/java/com/shortener/url/UrlServiceTest.java \
  api/src/main/java/com/shortener/url/CreateUrlRequest.java \
  api/src/main/java/com/shortener/url/CreateUrlResponse.java \
  api/src/main/java/com/shortener/web/ErrorResponse.java \
  api/src/main/java/com/shortener/validation/NonRecursiveUrl.java \
  api/src/main/java/com/shortener/validation/NonRecursiveUrlValidator.java \
  api/src/main/java/com/shortener/url/Url.java \
  api/src/main/java/com/shortener/url/UrlRepository.java \
  api/src/main/java/com/shortener/url/UrlService.java \
  api/src/main/java/com/shortener/url/UrlController.java \
  api/src/main/java/com/shortener/web/GlobalExceptionHandler.java \
  api/src/main/resources/application.yml
```

- [ ] **Step 2: Verify**

```bash
git status
```

Expected: exactly those 12 files under "Changes to be committed". No build output.

- [ ] **Step 3: Commit**

```bash
git commit -m "feat(step-5): URL creation endpoint"
```

- [ ] **Step 4: Verify**

```bash
git log --oneline | head -3
```

Expected: top commit is `feat(step-5): URL creation endpoint`.
