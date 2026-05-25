# Step 6 — Redirect Endpoint Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement `GET /{shortcode}` with tiered cache lookup (negative cache → positive cache → Cassandra with stampede lock), HTTP 302 redirect, and fire-and-forget RabbitMQ access event publishing.

**Architecture:** `RedirectController` extracts client metadata and delegates to `RedirectService`, which implements the four-stage lookup with a Redis NX lock for stampede prevention. `AccessEventPublisher` publishes events on a dedicated async thread pool so publish failures never touch the HTTP response. `RabbitMqTopology` declares all AMQP infrastructure at startup. TDD order: write failing service test first, then build all supporting types, then make test green, then wire controller.

**Tech Stack:** Spring MVC, Spring Data Cassandra (`UrlRepository`), Spring Data Redis (`StringRedisTemplate`), Spring AMQP (`RabbitTemplate`, `@RabbitListener` topology), `@Async` with `ThreadPoolTaskExecutor`, Mockito (unit tests)

---

## File Map

| File | Action |
|---|---|
| `api/src/test/java/com/shortener/redirect/RedirectServiceTest.java` | Create — failing first (TDD red) |
| `api/src/main/java/com/shortener/web/NotFoundException.java` | Create — checked 404 exception |
| `api/src/main/java/com/shortener/event/AccessEvent.java` | Create — AMQP message payload |
| `api/src/main/java/com/shortener/event/AccessEventPublisher.java` | Create — `@Async` AMQP publish |
| `api/src/main/java/com/shortener/config/AsyncConfig.java` | Create — bounded thread pool |
| `api/src/main/java/com/shortener/config/RabbitMqTopology.java` | Create — exchange/queue/binding beans |
| `api/src/main/java/com/shortener/redirect/RedirectService.java` | Create — four-stage cache lookup |
| `api/src/main/java/com/shortener/redirect/RedirectController.java` | Create — `GET /{shortcode}` |
| `api/src/main/java/com/shortener/web/GlobalExceptionHandler.java` | Modify — add `NotFoundException` handler |

---

### Task 1: Write failing RedirectServiceTest

**Files:**
- Create: `api/src/test/java/com/shortener/redirect/RedirectServiceTest.java`

- [ ] **Step 1: Create test package directory**

```bash
mkdir -p api/src/test/java/com/shortener/redirect
```

- [ ] **Step 2: Write RedirectServiceTest.java**

```java
package com.shortener.redirect;

import com.shortener.event.AccessEventPublisher;
import com.shortener.url.Url;
import com.shortener.url.UrlRepository;
import com.shortener.web.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RedirectServiceTest {

    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOps;
    @Mock UrlRepository urlRepository;
    @Mock AccessEventPublisher publisher;

    RedirectService service;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        service = new RedirectService(redisTemplate, urlRepository, publisher, 604800L, 60L);
    }

    @Test
    void negcache_hit_throws_notFound_without_db() {
        when(redisTemplate.hasKey("url:negcache:abc")).thenReturn(true);

        assertThrows(NotFoundException.class, () ->
            service.redirect("abc", "1.2.3.4", "TestAgent", null));

        verify(urlRepository, never()).findById(any());
        verify(publisher, never()).publish(any());
    }

    @Test
    void poscache_hit_returns_url_without_db() {
        when(redisTemplate.hasKey("url:negcache:abc")).thenReturn(false);
        when(valueOps.get("url:cache:abc")).thenReturn("https://example.com");

        String result = service.redirect("abc", "1.2.3.4", "TestAgent", null);

        assertEquals("https://example.com", result);
        verify(urlRepository, never()).findById(any());
        verify(publisher).publish(argThat(e ->
            e.getShortCode().equals("abc") &&
            e.getIpAddress().equals("1.2.3.4")
        ));
    }

    @Test
    void cache_miss_db_hit_populates_cache_and_returns_url() {
        when(redisTemplate.hasKey("url:negcache:abc")).thenReturn(false);
        when(valueOps.get("url:cache:abc")).thenReturn(null);
        when(valueOps.setIfAbsent(eq("url:lock:abc"), eq("1"), any())).thenReturn(true);
        Url url = new Url("abc", "https://example.com", 250001L, Instant.now());
        when(urlRepository.findById("abc")).thenReturn(Optional.of(url));

        String result = service.redirect("abc", "1.2.3.4", "TestAgent", null);

        assertEquals("https://example.com", result);
        verify(valueOps).set(eq("url:cache:abc"), eq("https://example.com"), any());
        verify(redisTemplate).delete("url:lock:abc");
        verify(publisher).publish(any());
    }

    @Test
    void cache_miss_db_miss_sets_negcache_and_throws() {
        when(redisTemplate.hasKey("url:negcache:abc")).thenReturn(false);
        when(valueOps.get("url:cache:abc")).thenReturn(null);
        when(valueOps.setIfAbsent(eq("url:lock:abc"), eq("1"), any())).thenReturn(true);
        when(urlRepository.findById("abc")).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () ->
            service.redirect("abc", "1.2.3.4", "TestAgent", null));

        verify(valueOps).set(eq("url:negcache:abc"), eq("0"), any());
        verify(redisTemplate).delete("url:lock:abc");
        verify(publisher, never()).publish(any());
    }

    @Test
    void lock_contention_retries_cache_and_hits_on_second_check() {
        when(redisTemplate.hasKey("url:negcache:abc")).thenReturn(false);
        // First cache check: miss; second cache check (after contention): hit
        when(valueOps.get("url:cache:abc")).thenReturn(null).thenReturn("https://example.com");
        when(valueOps.setIfAbsent(eq("url:lock:abc"), eq("1"), any())).thenReturn(false);

        String result = service.redirect("abc", "1.2.3.4", "TestAgent", null);

        assertEquals("https://example.com", result);
        verify(urlRepository, never()).findById(any());
        verify(publisher).publish(any());
    }
}
```

- [ ] **Step 3: Run — verify it FAILS (compile error)**

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 api/gradlew -p api test --tests "com.shortener.redirect.RedirectServiceTest" 2>&1 | tail -10
```

Expected: `BUILD FAILED` — `cannot find symbol` for `RedirectService`, `AccessEventPublisher`, `NotFoundException`. This is the TDD red phase.

---

### Task 2: Create NotFoundException

**Files:**
- Create: `api/src/main/java/com/shortener/web/NotFoundException.java`

- [ ] **Step 1: Write NotFoundException.java**

```java
package com.shortener.web;

public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
```

- [ ] **Step 2: Verify file exists**

```bash
ls api/src/main/java/com/shortener/web/
```

Expected: `ErrorResponse.java  GlobalExceptionHandler.java  NotFoundException.java`

---

### Task 3: Create AccessEvent

**Files:**
- Create: `api/src/main/java/com/shortener/event/AccessEvent.java`

- [ ] **Step 1: Create event package directory**

```bash
mkdir -p api/src/main/java/com/shortener/event
```

- [ ] **Step 2: Write AccessEvent.java**

```java
package com.shortener.event;

import java.time.Instant;

public class AccessEvent {

    private final String shortCode;
    private final Instant requestTime;
    private final String ipAddress;
    private final String userAgent;
    private final String referer;

    public AccessEvent(String shortCode, Instant requestTime, String ipAddress,
                       String userAgent, String referer) {
        this.shortCode = shortCode;
        this.requestTime = requestTime;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
        this.referer = referer;
    }

    public String getShortCode()   { return shortCode; }
    public Instant getRequestTime(){ return requestTime; }
    public String getIpAddress()   { return ipAddress; }
    public String getUserAgent()   { return userAgent; }
    public String getReferer()     { return referer; }
}
```

---

### Task 4: Create AccessEventPublisher

**Files:**
- Create: `api/src/main/java/com/shortener/event/AccessEventPublisher.java`

- [ ] **Step 1: Write AccessEventPublisher.java**

```java
package com.shortener.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
public class AccessEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(AccessEventPublisher.class);

    private final RabbitTemplate rabbitTemplate;

    public AccessEventPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @Async("accessEventExecutor")
    public void publish(AccessEvent event) {
        try {
            rabbitTemplate.convertAndSend("url.events", "url.accessed", event);
        } catch (Exception e) {
            log.warn("Failed to publish access event for {}: {}", event.getShortCode(), e.getMessage());
        }
    }
}
```

---

### Task 5: Create AsyncConfig and RabbitMqTopology

**Files:**
- Create: `api/src/main/java/com/shortener/config/AsyncConfig.java`
- Create: `api/src/main/java/com/shortener/config/RabbitMqTopology.java`

- [ ] **Step 1: Write AsyncConfig.java**

```java
package com.shortener.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean("accessEventExecutor")
    public Executor accessEventExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(2);
        exec.setMaxPoolSize(8);
        exec.setQueueCapacity(500);
        exec.setThreadNamePrefix("access-event-");
        exec.initialize();
        return exec;
    }
}
```

- [ ] **Step 2: Write RabbitMqTopology.java**

```java
package com.shortener.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMqTopology {

    @Bean
    public TopicExchange urlEventsExchange() {
        return new TopicExchange("url.events", true, false);
    }

    @Bean
    public DirectExchange urlEventsDlx() {
        return new DirectExchange("url.events.dlx", true, false);
    }

    @Bean
    public Queue accessLogQueue() {
        return QueueBuilder.durable("url.access.log")
                .withArgument("x-dead-letter-exchange", "url.events.dlx")
                .build();
    }

    @Bean
    public Queue accessLogDlq() {
        return QueueBuilder.durable("url.access.log.dlq").build();
    }

    @Bean
    public Binding accessLogBinding() {
        return BindingBuilder.bind(accessLogQueue())
                .to(urlEventsExchange())
                .with("url.accessed");
    }

    @Bean
    public Binding dlqBinding() {
        return BindingBuilder.bind(accessLogDlq())
                .to(urlEventsDlx())
                .with("url.access.log");
    }
}
```

---

### Task 6: Implement RedirectService — verify test passes

**Files:**
- Create: `api/src/main/java/com/shortener/redirect/RedirectService.java`

- [ ] **Step 1: Create redirect package directory**

```bash
mkdir -p api/src/main/java/com/shortener/redirect
```

- [ ] **Step 2: Write RedirectService.java**

```java
package com.shortener.redirect;

import com.shortener.event.AccessEvent;
import com.shortener.event.AccessEventPublisher;
import com.shortener.url.UrlRepository;
import com.shortener.web.NotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

@Service
public class RedirectService {

    private static final int MAX_LOCK_RETRIES = 3;
    private static final long LOCK_SLEEP_MS = 50;

    private final StringRedisTemplate redisTemplate;
    private final UrlRepository urlRepository;
    private final AccessEventPublisher publisher;
    private final long cacheTtlSeconds;
    private final long negCacheTtlSeconds;

    public RedirectService(StringRedisTemplate redisTemplate,
                           UrlRepository urlRepository,
                           AccessEventPublisher publisher,
                           @Value("${shortener.cache-ttl-seconds}") long cacheTtlSeconds,
                           @Value("${shortener.negcache-ttl-seconds}") long negCacheTtlSeconds) {
        this.redisTemplate = redisTemplate;
        this.urlRepository = urlRepository;
        this.publisher = publisher;
        this.cacheTtlSeconds = cacheTtlSeconds;
        this.negCacheTtlSeconds = negCacheTtlSeconds;
    }

    public String redirect(String shortcode, String ip, String userAgent, String referer) {
        // 1. Negative cache
        if (Boolean.TRUE.equals(redisTemplate.hasKey("url:negcache:" + shortcode))) {
            throw new NotFoundException("Short code not found: " + shortcode);
        }

        // 2. Positive cache
        String cached = redisTemplate.opsForValue().get("url:cache:" + shortcode);
        if (cached != null) {
            publishEvent(shortcode, ip, userAgent, referer);
            return cached;
        }

        // 3. Stampede lock with retries
        for (int attempt = 0; attempt < MAX_LOCK_RETRIES; attempt++) {
            Boolean locked = redisTemplate.opsForValue()
                    .setIfAbsent("url:lock:" + shortcode, "1", Duration.ofSeconds(5));
            if (Boolean.TRUE.equals(locked)) {
                return lookupAndCache(shortcode, ip, userAgent, referer);
            }
            // Lock contention — sleep and retry cache read
            try { Thread.sleep(LOCK_SLEEP_MS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            String retryCache = redisTemplate.opsForValue().get("url:cache:" + shortcode);
            if (retryCache != null) {
                publishEvent(shortcode, ip, userAgent, referer);
                return retryCache;
            }
        }

        // 4. Fall through to direct DB read (lock exhausted)
        return lookupAndCache(shortcode, ip, userAgent, referer);
    }

    private String lookupAndCache(String shortcode, String ip, String userAgent, String referer) {
        return urlRepository.findById(shortcode).map(url -> {
            redisTemplate.opsForValue().set(
                "url:cache:" + shortcode, url.getLongUrl(), Duration.ofSeconds(cacheTtlSeconds));
            redisTemplate.delete("url:lock:" + shortcode);
            publishEvent(shortcode, ip, userAgent, referer);
            return url.getLongUrl();
        }).orElseGet(() -> {
            redisTemplate.opsForValue().set(
                "url:negcache:" + shortcode, "0", Duration.ofSeconds(negCacheTtlSeconds));
            redisTemplate.delete("url:lock:" + shortcode);
            throw new NotFoundException("Short code not found: " + shortcode);
        });
    }

    private void publishEvent(String shortcode, String ip, String userAgent, String referer) {
        publisher.publish(new AccessEvent(shortcode, Instant.now(), ip, userAgent, referer));
    }
}
```

- [ ] **Step 3: Run RedirectServiceTest — verify all 5 tests PASS**

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 api/gradlew -p api test --tests "com.shortener.redirect.RedirectServiceTest" 2>&1 | tail -8
```

Expected: `BUILD SUCCESSFUL`, `5 tests completed, 0 failed`.

---

### Task 7: Create RedirectController and update GlobalExceptionHandler

**Files:**
- Create: `api/src/main/java/com/shortener/redirect/RedirectController.java`
- Modify: `api/src/main/java/com/shortener/web/GlobalExceptionHandler.java`

- [ ] **Step 1: Write RedirectController.java**

```java
package com.shortener.redirect;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
public class RedirectController {

    private final RedirectService redirectService;

    public RedirectController(RedirectService redirectService) {
        this.redirectService = redirectService;
    }

    @GetMapping("/{shortcode}")
    public ResponseEntity<Void> redirect(@PathVariable String shortcode,
                                         HttpServletRequest request) {
        String ip = extractIp(request);
        String userAgent = request.getHeader("User-Agent");
        String referer = request.getHeader("Referer");

        String longUrl = redirectService.redirect(shortcode, ip, userAgent, referer);

        return ResponseEntity.status(302)
                .location(URI.create(longUrl))
                .header("Cache-Control", "no-store, max-age=0")
                .build();
    }

    private String extractIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
```

- [ ] **Step 2: Update GlobalExceptionHandler.java**

Replace the entire file content with:

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

    @ExceptionHandler(NotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse handleNotFound(NotFoundException ex) {
        return new ErrorResponse(ex.getMessage(), List.of());
    }
}
```

- [ ] **Step 3: Run full test suite**

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 api/gradlew -p api test 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`.

---

### Task 8: Runtime verification

- [ ] **Step 1: Ensure Docker Compose infra is running**

```bash
docker compose ps --format '{{.Name}} {{.Health}}' 2>/dev/null | grep -E 'cassandra|redis|rabbitmq'
```

If any are missing or unhealthy:
```bash
docker compose up -d cassandra redis rabbitmq
for i in $(seq 1 30); do
  status=$(docker compose ps --format '{{.Health}}' cassandra 2>/dev/null | head -1)
  [ "$status" = "healthy" ] && echo "Cassandra healthy" && break
  echo "Attempt $i/30 — $status"; sleep 5
done
```

- [ ] **Step 2: Build jar**

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 api/gradlew -p api build -x test 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Start app**

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 java \
  -jar api/build/libs/url-shortener-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=local \
  > /tmp/spring-step6.log 2>&1 &
echo $! > /tmp/spring-step6.pid
```

- [ ] **Step 4: Wait for startup**

```bash
for i in $(seq 1 30); do
  code=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/actuator/health 2>/dev/null)
  [ "$code" = "200" ] && echo "App started!" && break
  echo "Attempt $i/30 — HTTP $code"; sleep 2
done
```

If it doesn't start, check: `tail -60 /tmp/spring-step6.log`

- [ ] **Step 5: Create a short URL**

```bash
SHORT_CODE=$(curl -s -X POST http://localhost:8080/api/v1/urls \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com/redirect-test"}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['short_code'])")
echo "SHORT_CODE=$SHORT_CODE"
```

- [ ] **Step 6: Verify redirect returns 302**

```bash
curl -s -I http://localhost:8080/$SHORT_CODE
```

Expected: `HTTP/1.1 302`, `Location: https://example.com/redirect-test`, `Cache-Control: no-store, max-age=0`

- [ ] **Step 7: Verify 404 for unknown code**

```bash
curl -s -w "\nHTTP:%{http_code}" http://localhost:8080/doesnotexist
```

Expected: HTTP 404 with JSON `{"message":"Short code not found: doesnotexist","errors":[]}`.

- [ ] **Step 8: Verify second 404 hit is served from negative cache (no Cassandra call)**

```bash
curl -s -w "\nHTTP:%{http_code}" http://localhost:8080/doesnotexist
docker compose exec redis redis-cli EXISTS "url:negcache:doesnotexist"
```

Expected: second request also returns 404; Redis `EXISTS` returns `1`.

- [ ] **Step 9: Verify event in RabbitMQ**

```bash
curl -s -u guest:guest http://localhost:15672/api/queues/%2F/url.access.log | python3 -c "import sys,json; q=json.load(sys.stdin); print('messages:', q.get('messages',0))"
```

Expected: `messages: 1` (or more if you made multiple redirects).

- [ ] **Step 10: Verify redirect works when RabbitMQ is stopped**

```bash
docker compose stop rabbitmq
sleep 2
curl -s -I http://localhost:8080/$SHORT_CODE
docker compose start rabbitmq
```

Expected: still returns `HTTP/1.1 302` with correct `Location` even with RabbitMQ down. The warning in the log is acceptable.

- [ ] **Step 11: Stop app**

```bash
kill $(cat /tmp/spring-step6.pid) 2>/dev/null; rm -f /tmp/spring-step6.pid /tmp/spring-step6.log
```

---

### Task 9: Commit

- [ ] **Step 1: Stage all files**

```bash
git add \
  api/src/test/java/com/shortener/redirect/RedirectServiceTest.java \
  api/src/main/java/com/shortener/web/NotFoundException.java \
  api/src/main/java/com/shortener/event/AccessEvent.java \
  api/src/main/java/com/shortener/event/AccessEventPublisher.java \
  api/src/main/java/com/shortener/config/AsyncConfig.java \
  api/src/main/java/com/shortener/config/RabbitMqTopology.java \
  api/src/main/java/com/shortener/redirect/RedirectService.java \
  api/src/main/java/com/shortener/redirect/RedirectController.java \
  api/src/main/java/com/shortener/web/GlobalExceptionHandler.java
```

- [ ] **Step 2: Verify staged files**

```bash
git status
```

Expected: exactly 9 files under "Changes to be committed".

- [ ] **Step 3: Commit**

```bash
git commit -m "feat(step-6): redirect endpoint with cache and async event publishing"
```

- [ ] **Step 4: Verify**

```bash
git log --oneline | head -3
```

Expected: top commit is `feat(step-6): redirect endpoint with cache and async event publishing`.
