# Step 10 — Observability: Prometheus + Grafana Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enable RabbitMQ Prometheus scraping, add six custom Micrometer metrics to Spring services, and provision a Grafana dashboard with all required SLI panels.

**Architecture:** Custom metrics are added directly to `UrlService`, `RedirectService`, and `AccessLogConsumer` via constructor-injected `MeterRegistry`. Grafana is auto-provisioned via YAML files in `observability/grafana/provisioning/`. The `rabbitmq_prometheus` plugin is enabled via a mounted `enabled_plugins` file.

**Tech Stack:** Micrometer 1.13 (bundled with Spring Boot 3.3), `io.micrometer.core.instrument.{Counter,Timer,TimeGauge}`, `SimpleMeterRegistry` for unit tests, Grafana 10 dashboard schema v38, RabbitMQ `rabbitmq_prometheus` plugin (port 15692).

---

## File Structure

| File | Action | Purpose |
|---|---|---|
| `rabbitmq/enabled_plugins` | Create | Enables `rabbitmq_prometheus` plugin |
| `docker-compose.yml` | Modify | Mount `enabled_plugins`, expose port 15692 |
| `observability/prometheus.yml` | Modify | Add rabbitmq + prometheus self-scrape jobs |
| `api/src/main/java/com/shortener/url/UrlService.java` | Modify | Add `shortener.urls.created` counter |
| `api/src/test/java/com/shortener/url/UrlServiceTest.java` | Modify | Pass `SimpleMeterRegistry`, assert counter |
| `api/src/main/java/com/shortener/redirect/RedirectService.java` | Modify | Add redirect/cache counters + latency timer |
| `api/src/test/java/com/shortener/redirect/RedirectServiceTest.java` | Modify | Pass `SimpleMeterRegistry`, assert counters |
| `api/src/main/java/com/shortener/consumer/AccessLogConsumer.java` | Modify | Add `shortener.log.consumer.lag` TimeGauge |
| `api/src/test/java/com/shortener/consumer/AccessLogConsumerTest.java` | Modify | Pass `SimpleMeterRegistry`, assert lag gauge |
| `observability/grafana/provisioning/datasources/prometheus.yml` | Create | Auto-provision Prometheus datasource |
| `observability/grafana/provisioning/dashboards/shortener.yml` | Create | Auto-provision dashboard file provider |
| `observability/grafana/dashboards/shortener.json` | Create | Dashboard JSON with all 7 SLI panels |

---

### Task 1: RabbitMQ Prometheus plugin + scrape config

**Files:**
- Create: `rabbitmq/enabled_plugins`
- Modify: `docker-compose.yml`
- Modify: `observability/prometheus.yml`

No Java changes. No tests needed — verified by checking Prometheus targets.

- [ ] **Step 1: Create `rabbitmq/enabled_plugins`**

```
[rabbitmq_management,rabbitmq_prometheus].
```

The period at the end is required (Erlang term syntax). This file enables both the management UI (port 15672) and the Prometheus exporter (port 15692) on startup.

- [ ] **Step 2: Mount the file and expose port 15692 in `docker-compose.yml`**

Find the `rabbitmq:` service block (starts at line 73). Replace it with:

```yaml
  rabbitmq:
    image: rabbitmq:3.13-management
    container_name: rabbitmq
    networks:
      - shortener-net
    ports:
      - "5672:5672"
      - "15672:15672"
      - "15692:15692"
    volumes:
      - ./rabbitmq/enabled_plugins:/etc/rabbitmq/enabled_plugins:ro
    healthcheck:
      test: ["CMD", "rabbitmq-diagnostics", "ping"]
      interval: 10s
      timeout: 5s
      retries: 10
      start_period: 30s
```

- [ ] **Step 3: Add rabbitmq and prometheus self-scrape jobs to `observability/prometheus.yml`**

Replace the entire file content with:

```yaml
global:
  scrape_interval: 10s
  evaluation_interval: 10s

scrape_configs:
  - job_name: url-shortener-api
    metrics_path: /actuator/prometheus
    static_configs:
      - targets:
          - api-1:8080
          - api-2:8080
          - api-3:8080

  - job_name: rabbitmq
    static_configs:
      - targets:
          - rabbitmq:15692

  - job_name: prometheus
    static_configs:
      - targets:
          - localhost:9090
```

- [ ] **Step 4: Verify**

```bash
cd /home/guilherme/Systems/Projects/url-shortener && docker compose config --quiet 2>&1 | head -5
```
Expected: no errors (empty output or version warning only).

- [ ] **Step 5: Commit**

```bash
git add rabbitmq/enabled_plugins docker-compose.yml observability/prometheus.yml
git commit -m "feat(step-10): enable RabbitMQ prometheus plugin and update scrape config"
```

---

### Task 2: `shortener_urls_created_total` counter in UrlService

**Files:**
- Modify: `api/src/main/java/com/shortener/url/UrlService.java`
- Modify: `api/src/test/java/com/shortener/url/UrlServiceTest.java`

**Prometheus output name:** `shortener_urls_created_total` (Micrometer counter `shortener.urls.created` + Prometheus `_total` suffix).

- [ ] **Step 1: Write the failing test**

In `UrlServiceTest.java`, add the import and update `setUp` to pass a `SimpleMeterRegistry`. Then add a new test:

```java
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

// In setUp():
service = new UrlService(redisTemplate, urlRepository, encoder, "http://localhost", 604800L,
        new SimpleMeterRegistry());

// New test method:
@Test
void create_increments_urls_created_counter() {
    when(valueOps.increment("url:counter")).thenReturn(250001L);
    when(encoder.encode(250001L)).thenReturn("Zx7q");

    service.create("https://example.com/a");
    service.create("https://example.com/b");
    // second call needs its own stubs
    when(valueOps.increment("url:counter")).thenReturn(250002L);
    when(encoder.encode(250002L)).thenReturn("Ab3m");
    service.create("https://example.com/b");

    // Re-create with a fresh registry to assert via the same instance
}
```

Actually, the cleanest test keeps the registry accessible. Here is the complete updated `UrlServiceTest.java`:

```java
package com.shortener.url;

import com.shortener.encoding.Base62Encoder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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

    SimpleMeterRegistry meterRegistry;
    UrlService service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        service = new UrlService(redisTemplate, urlRepository, encoder, "http://localhost", 604800L, meterRegistry);
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

    @Test
    void create_increments_urls_created_counter() {
        when(valueOps.increment("url:counter")).thenReturn(250000L);
        when(encoder.encode(250000L)).thenReturn("Hk2p");

        service.create("https://example.com/path");

        assertEquals(1.0, meterRegistry.counter("shortener.urls.created").count());
    }
}
```

- [ ] **Step 2: Run test to confirm it fails**

```bash
cd /home/guilherme/Systems/Projects/url-shortener/api && ./gradlew test --tests "com.shortener.url.UrlServiceTest.create_increments_urls_created_counter" --no-daemon 2>&1 | tail -15
```
Expected: compilation error — `UrlService` constructor does not yet accept `MeterRegistry`.

- [ ] **Step 3: Update `UrlService.java`**

Replace the full file:

```java
package com.shortener.url;

import com.shortener.encoding.Base62Encoder;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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
    private final long cacheTtlSeconds;
    private final Counter urlsCreatedCounter;

    public UrlService(StringRedisTemplate redisTemplate,
                      UrlRepository urlRepository,
                      Base62Encoder encoder,
                      @Value("${shortener.base-url}") String baseUrl,
                      @Value("${shortener.cache-ttl-seconds}") long cacheTtlSeconds,
                      MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.urlRepository = urlRepository;
        this.encoder = encoder;
        this.baseUrl = baseUrl;
        this.cacheTtlSeconds = cacheTtlSeconds;
        this.urlsCreatedCounter = Counter.builder("shortener.urls.created")
                .description("Total URLs created")
                .register(meterRegistry);
    }

    public CreateUrlResponse create(String longUrl) {
        Long rawId = redisTemplate.opsForValue().increment("url:counter");
        if (rawId == null) throw new IllegalStateException("Redis counter returned null");
        long id = rawId;
        String code = encoder.encode(id);
        Instant now = Instant.now();
        urlRepository.save(new Url(code, longUrl, id, now));
        redisTemplate.opsForValue().set("url:cache:" + code, longUrl, Duration.ofSeconds(cacheTtlSeconds));
        urlsCreatedCounter.increment();
        return new CreateUrlResponse(code, baseUrl + "/" + code, longUrl, now);
    }
}
```

- [ ] **Step 4: Run tests to confirm they pass**

```bash
cd /home/guilherme/Systems/Projects/url-shortener/api && ./gradlew test --tests "com.shortener.url.UrlServiceTest" --no-daemon 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`, both tests PASSED.

- [ ] **Step 5: Commit**

```bash
git add api/src/main/java/com/shortener/url/UrlService.java api/src/test/java/com/shortener/url/UrlServiceTest.java
git commit -m "feat(step-10): shortener_urls_created_total counter in UrlService"
```

---

### Task 3: Redirect counters + latency timer in RedirectService

**Files:**
- Modify: `api/src/main/java/com/shortener/redirect/RedirectService.java`
- Modify: `api/src/test/java/com/shortener/redirect/RedirectServiceTest.java`

**Metrics added:**
- `shortener_redirects_total{result="hit"}` — positive cache or DB hit, served with 302
- `shortener_redirects_total{result="miss"}` — cache miss, served from DB (still 302)
- `shortener_redirects_total{result="notfound"}` — 404 (from negcache or DB miss)
- `shortener_cache_hits_total{type="positive"}` — served from Redis positive cache
- `shortener_cache_hits_total{type="negative"}` — 404 short-circuited from Redis negative cache
- `shortener_cache_misses_total` — fell through to Cassandra lookup
- `shortener_redirect_latency_seconds` — Timer wrapping entire redirect call (histogram, p99-capable)

**Metric increment points:**
| Condition | Metrics incremented |
|---|---|
| Negative cache hit | `cache_hits{type=negative}`, `redirects{result=notfound}` |
| Positive cache hit (first check or lock-retry) | `cache_hits{type=positive}`, `redirects{result=hit}` |
| Falls through to DB | `cache_misses` |
| DB found | `redirects{result=miss}` |
| DB not found | `redirects{result=notfound}` |

- [ ] **Step 1: Write the updated `RedirectServiceTest.java`**

Replace the entire file:

```java
package com.shortener.redirect;

import com.shortener.event.AccessEventPublisher;
import com.shortener.url.Url;
import com.shortener.url.UrlRepository;
import com.shortener.web.NotFoundException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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

    SimpleMeterRegistry meterRegistry;
    RedirectService service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        service = new RedirectService(redisTemplate, urlRepository, publisher, 604800L, 60L, meterRegistry);
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
        when(valueOps.get("url:cache:abc")).thenReturn(null).thenReturn("https://example.com");
        when(valueOps.setIfAbsent(eq("url:lock:abc"), eq("1"), any())).thenReturn(false);

        String result = service.redirect("abc", "1.2.3.4", "TestAgent", null);

        assertEquals("https://example.com", result);
        verify(urlRepository, never()).findById(any());
        verify(publisher).publish(any());
    }

    @Test
    void negcache_hit_increments_cache_negative_and_notfound_counters() {
        when(redisTemplate.hasKey("url:negcache:abc")).thenReturn(true);

        assertThrows(NotFoundException.class, () ->
            service.redirect("abc", "1.2.3.4", "TestAgent", null));

        assertEquals(1.0, meterRegistry.counter("shortener.cache.hits", "type", "negative").count());
        assertEquals(1.0, meterRegistry.counter("shortener.redirects", "result", "notfound").count());
    }

    @Test
    void poscache_hit_increments_cache_positive_and_hit_counters() {
        when(redisTemplate.hasKey("url:negcache:abc")).thenReturn(false);
        when(valueOps.get("url:cache:abc")).thenReturn("https://example.com");

        service.redirect("abc", "1.2.3.4", "TestAgent", null);

        assertEquals(1.0, meterRegistry.counter("shortener.cache.hits", "type", "positive").count());
        assertEquals(1.0, meterRegistry.counter("shortener.redirects", "result", "hit").count());
    }

    @Test
    void db_hit_increments_cache_miss_and_miss_redirect_counters() {
        when(redisTemplate.hasKey("url:negcache:abc")).thenReturn(false);
        when(valueOps.get("url:cache:abc")).thenReturn(null);
        when(valueOps.setIfAbsent(eq("url:lock:abc"), eq("1"), any())).thenReturn(true);
        when(urlRepository.findById("abc")).thenReturn(
            Optional.of(new Url("abc", "https://example.com", 1L, Instant.now())));

        service.redirect("abc", "1.2.3.4", "TestAgent", null);

        assertEquals(1.0, meterRegistry.counter("shortener.cache.misses").count());
        assertEquals(1.0, meterRegistry.counter("shortener.redirects", "result", "miss").count());
    }

    @Test
    void redirect_latency_timer_records_on_every_call() {
        when(redisTemplate.hasKey("url:negcache:abc")).thenReturn(false);
        when(valueOps.get("url:cache:abc")).thenReturn("https://example.com");

        service.redirect("abc", "1.2.3.4", "TestAgent", null);

        assertEquals(1L, meterRegistry.timer("shortener.redirect.latency").count());
    }
}
```

- [ ] **Step 2: Run tests to confirm they fail**

```bash
cd /home/guilherme/Systems/Projects/url-shortener/api && ./gradlew test --tests "com.shortener.redirect.RedirectServiceTest" --no-daemon 2>&1 | tail -15
```
Expected: compilation error — `RedirectService` constructor does not yet accept `MeterRegistry`.

- [ ] **Step 3: Replace `RedirectService.java`**

```java
package com.shortener.redirect;

import com.shortener.event.AccessEvent;
import com.shortener.event.AccessEventPublisher;
import com.shortener.url.UrlRepository;
import com.shortener.web.NotFoundException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
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
    private final org.springframework.data.redis.core.ValueOperations<String, String> valueOps;
    private final UrlRepository urlRepository;
    private final AccessEventPublisher publisher;
    private final long cacheTtlSeconds;
    private final long negCacheTtlSeconds;

    private final MeterRegistry meterRegistry;
    private final Counter cacheHitPositive;
    private final Counter cacheHitNegative;
    private final Counter cacheMiss;
    private final Counter redirectHit;
    private final Counter redirectMiss;
    private final Counter redirectNotFound;
    private final Timer redirectLatencyTimer;

    public RedirectService(StringRedisTemplate redisTemplate,
                           UrlRepository urlRepository,
                           AccessEventPublisher publisher,
                           @Value("${shortener.cache-ttl-seconds}") long cacheTtlSeconds,
                           @Value("${shortener.negcache-ttl-seconds}") long negCacheTtlSeconds,
                           MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.valueOps = redisTemplate.opsForValue();
        this.urlRepository = urlRepository;
        this.publisher = publisher;
        this.cacheTtlSeconds = cacheTtlSeconds;
        this.negCacheTtlSeconds = negCacheTtlSeconds;
        this.meterRegistry = meterRegistry;
        this.cacheHitPositive = Counter.builder("shortener.cache.hits").tag("type", "positive").register(meterRegistry);
        this.cacheHitNegative = Counter.builder("shortener.cache.hits").tag("type", "negative").register(meterRegistry);
        this.cacheMiss = Counter.builder("shortener.cache.misses").register(meterRegistry);
        this.redirectHit = Counter.builder("shortener.redirects").tag("result", "hit").register(meterRegistry);
        this.redirectMiss = Counter.builder("shortener.redirects").tag("result", "miss").register(meterRegistry);
        this.redirectNotFound = Counter.builder("shortener.redirects").tag("result", "notfound").register(meterRegistry);
        this.redirectLatencyTimer = Timer.builder("shortener.redirect.latency")
                .description("End-to-end redirect request latency")
                .publishPercentileHistogram()
                .register(meterRegistry);
    }

    public String redirect(String shortcode, String ip, String userAgent, String referer) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            return doRedirect(shortcode, ip, userAgent, referer);
        } finally {
            sample.stop(redirectLatencyTimer);
        }
    }

    private String doRedirect(String shortcode, String ip, String userAgent, String referer) {
        // 1. Negative cache
        if (Boolean.TRUE.equals(redisTemplate.hasKey("url:negcache:" + shortcode))) {
            cacheHitNegative.increment();
            redirectNotFound.increment();
            throw new NotFoundException("Short code not found: " + shortcode);
        }

        // 2. Positive cache
        String cached = valueOps.get("url:cache:" + shortcode);
        if (cached != null) {
            cacheHitPositive.increment();
            redirectHit.increment();
            publishEvent(shortcode, ip, userAgent, referer);
            return cached;
        }

        // 3. Cache miss — going to DB
        cacheMiss.increment();

        // 4. Stampede lock with retries
        for (int attempt = 0; attempt < MAX_LOCK_RETRIES; attempt++) {
            Boolean locked = valueOps.setIfAbsent("url:lock:" + shortcode, "1", Duration.ofSeconds(5));
            if (Boolean.TRUE.equals(locked)) {
                return lookupAndCache(shortcode, ip, userAgent, referer, true);
            }
            try { Thread.sleep(LOCK_SLEEP_MS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            String retryCache = valueOps.get("url:cache:" + shortcode);
            if (retryCache != null) {
                cacheHitPositive.increment();
                redirectHit.increment();
                publishEvent(shortcode, ip, userAgent, referer);
                return retryCache;
            }
        }

        // 5. Fall through to direct DB read (lock exhausted)
        return lookupAndCache(shortcode, ip, userAgent, referer, false);
    }

    private String lookupAndCache(String shortcode, String ip, String userAgent, String referer, boolean holdingLock) {
        return urlRepository.findById(shortcode).map(url -> {
            valueOps.set("url:cache:" + shortcode, url.getLongUrl(), Duration.ofSeconds(cacheTtlSeconds));
            if (holdingLock) redisTemplate.delete("url:lock:" + shortcode);
            redirectMiss.increment();
            publishEvent(shortcode, ip, userAgent, referer);
            return url.getLongUrl();
        }).orElseGet(() -> {
            valueOps.set("url:negcache:" + shortcode, "0", Duration.ofSeconds(negCacheTtlSeconds));
            if (holdingLock) redisTemplate.delete("url:lock:" + shortcode);
            redirectNotFound.increment();
            throw new NotFoundException("Short code not found: " + shortcode);
        });
    }

    private void publishEvent(String shortcode, String ip, String userAgent, String referer) {
        publisher.publish(new AccessEvent(shortcode, Instant.now(), ip, userAgent, referer));
    }
}
```

- [ ] **Step 4: Run tests**

```bash
cd /home/guilherme/Systems/Projects/url-shortener/api && ./gradlew test --tests "com.shortener.redirect.RedirectServiceTest" --no-daemon 2>&1 | tail -15
```
Expected: `BUILD SUCCESSFUL`, all 9 tests PASSED.

- [ ] **Step 5: Commit**

```bash
git add api/src/main/java/com/shortener/redirect/RedirectService.java api/src/test/java/com/shortener/redirect/RedirectServiceTest.java
git commit -m "feat(step-10): redirect counters and latency timer in RedirectService"
```

---

### Task 4: `shortener_log_consumer_lag_seconds` gauge in AccessLogConsumer

**Files:**
- Modify: `api/src/main/java/com/shortener/consumer/AccessLogConsumer.java`
- Modify: `api/src/test/java/com/shortener/consumer/AccessLogConsumerTest.java`

`TimeGauge` reports `now - event.requestTime` measured at consumer write time. Stored in an `AtomicLong` (nanoseconds); `TimeGauge` converts to seconds for Prometheus.

- [ ] **Step 1: Write the failing test**

In `AccessLogConsumerTest.java`, add:

```java
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.TimeUnit;
```

Update `setUp()` and add a new test. Complete updated file:

```java
package com.shortener.consumer;

import com.datastax.oss.driver.api.core.cql.Statement;
import com.rabbitmq.client.Channel;
import com.shortener.event.AccessEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.cassandra.core.CassandraOperations;
import org.springframework.retry.support.RetryTemplate;

import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccessLogConsumerTest {

    @Mock AccessLogRepository accessLogRepository;
    @Mock CassandraOperations cassandraOperations;
    @Mock Channel channel;

    SimpleMeterRegistry meterRegistry;
    AccessLogConsumer consumer;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        RetryTemplate retryTemplate = RetryTemplate.builder().maxAttempts(3).fixedBackoff(1).build();
        consumer = new AccessLogConsumer(accessLogRepository, cassandraOperations, retryTemplate, meterRegistry);
    }

    @Test
    void successful_consumption_saves_entry_and_acks() throws IOException {
        AccessEvent event = new AccessEvent("abc1", Instant.parse("2026-05-25T10:00:00Z"),
                "1.2.3.4", "Mozilla/5.0", "https://example.com");

        consumer.consume(event, channel, 1L);

        verify(accessLogRepository).save(any(AccessLogEntry.class));
        verify(cassandraOperations).execute(any(Statement.class));
        verify(channel).basicAck(1L, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    void time_bucket_is_formatted_as_yyyy_MM_dd_HH_utc() throws IOException {
        AccessEvent event = new AccessEvent("abc1", Instant.parse("2026-05-25T14:30:00Z"),
                "1.2.3.4", "Mozilla/5.0", null);

        consumer.consume(event, channel, 1L);

        ArgumentCaptor<AccessLogEntry> captor = ArgumentCaptor.forClass(AccessLogEntry.class);
        verify(accessLogRepository).save(captor.capture());
        assertEquals("2026-05-25-14", captor.getValue().getKey().getTimeBucket());
    }

    @Test
    void failure_after_all_retries_nacks_without_requeue() throws IOException {
        AccessEvent event = new AccessEvent("abc1", Instant.now(), "1.2.3.4", "ua", null);
        doThrow(new RuntimeException("Cassandra down")).when(accessLogRepository).save(any());

        consumer.consume(event, channel, 2L);

        verify(channel).basicNack(2L, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    void retries_three_times_before_giving_up() throws IOException {
        AccessEvent event = new AccessEvent("abc1", Instant.now(), "1.2.3.4", "ua", null);
        doThrow(new RuntimeException("transient")).when(accessLogRepository).save(any());

        consumer.consume(event, channel, 3L);

        verify(accessLogRepository, times(3)).save(any());
        verify(channel).basicNack(3L, false, false);
    }

    @Test
    void lag_gauge_is_positive_after_successful_consume() throws IOException {
        // Event created 2 seconds ago
        Instant requestTime = Instant.now().minusSeconds(2);
        AccessEvent event = new AccessEvent("abc1", requestTime, "1.2.3.4", "ua", null);

        consumer.consume(event, channel, 1L);

        double lagSeconds = meterRegistry.get("shortener.log.consumer.lag").timeGauge().value(TimeUnit.SECONDS);
        assertTrue(lagSeconds >= 2.0, "Expected lag >= 2s, got " + lagSeconds);
    }
}
```

- [ ] **Step 2: Run test to confirm it fails**

```bash
cd /home/guilherme/Systems/Projects/url-shortener/api && ./gradlew test --tests "com.shortener.consumer.AccessLogConsumerTest.lag_gauge_is_positive_after_successful_consume" --no-daemon 2>&1 | tail -15
```
Expected: compilation error — constructor does not accept `MeterRegistry`.

- [ ] **Step 3: Replace `AccessLogConsumer.java`**

```java
package com.shortener.consumer;

import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.datastax.oss.driver.api.core.uuid.Uuids;
import com.rabbitmq.client.Channel;
import com.shortener.event.AccessEvent;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.TimeGauge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.cassandra.core.CassandraOperations;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class AccessLogConsumer {

    private static final Logger log = LoggerFactory.getLogger(AccessLogConsumer.class);
    private static final DateTimeFormatter BUCKET_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HH").withZone(ZoneOffset.UTC);

    private final AccessLogRepository accessLogRepository;
    private final CassandraOperations cassandraOperations;
    private final RetryTemplate retryTemplate;
    private final AtomicLong lagNanos = new AtomicLong(0);

    public AccessLogConsumer(AccessLogRepository accessLogRepository,
                             CassandraOperations cassandraOperations,
                             @Qualifier("accessLogRetryTemplate") RetryTemplate retryTemplate,
                             MeterRegistry meterRegistry) {
        this.accessLogRepository = accessLogRepository;
        this.cassandraOperations = cassandraOperations;
        this.retryTemplate = retryTemplate;
        TimeGauge.builder("shortener.log.consumer.lag", lagNanos, TimeUnit.NANOSECONDS,
                        v -> (double) v.get())
                .description("Lag between event creation and Cassandra write")
                .register(meterRegistry);
    }

    @RabbitListener(queues = "url.access.log", ackMode = "MANUAL")
    public void consume(AccessEvent event, Channel channel,
                        @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        try {
            retryTemplate.execute(ctx -> {
                if (ctx.getRetryCount() > 0) {
                    log.warn("Retry {} for access log write on shortcode {}", ctx.getRetryCount(), event.getShortCode());
                }
                String timeBucket = BUCKET_FMT.format(event.getRequestTime());
                var day = event.getRequestTime().atZone(ZoneOffset.UTC).toLocalDate();
                AccessLogKey key = new AccessLogKey(
                        event.getShortCode(), timeBucket, event.getRequestTime(), Uuids.timeBased());
                accessLogRepository.save(
                        new AccessLogEntry(key, event.getIpAddress(), event.getUserAgent(), event.getReferer()));
                cassandraOperations.execute(SimpleStatement.newInstance(
                        "UPDATE shortener.access_counts SET count = count + 1 WHERE short_code = ? AND day = ?",
                        event.getShortCode(), day));
                lagNanos.set(Duration.between(event.getRequestTime(), Instant.now()).toNanos());
                return null;
            });
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("Failed to persist access event for {} after all retries: {}", event.getShortCode(), e.getMessage());
            try {
                channel.basicNack(deliveryTag, false, false);
            } catch (IOException ioEx) {
                log.error("Failed to nack message {}: {}", deliveryTag, ioEx.getMessage());
            }
        }
    }
}
```

- [ ] **Step 4: Run all tests**

```bash
cd /home/guilherme/Systems/Projects/url-shortener/api && ./gradlew test --no-daemon 2>&1 | tail -20
```
Expected: `BUILD SUCCESSFUL`, all tests PASSED.

- [ ] **Step 5: Commit**

```bash
git add api/src/main/java/com/shortener/consumer/AccessLogConsumer.java api/src/test/java/com/shortener/consumer/AccessLogConsumerTest.java
git commit -m "feat(step-10): log consumer lag TimeGauge in AccessLogConsumer"
```

---

### Task 5: Grafana datasource + dashboard provisioning

**Files:**
- Create: `observability/grafana/provisioning/datasources/prometheus.yml`
- Create: `observability/grafana/provisioning/dashboards/shortener.yml`
- Create: `observability/grafana/dashboards/shortener.json`

No Java code. No unit tests — verified by opening Grafana in the browser after `docker compose up`.

- [ ] **Step 1: Create Grafana datasource provisioning**

Create directory and file:
```bash
mkdir -p /home/guilherme/Systems/Projects/url-shortener/observability/grafana/provisioning/datasources
```

`observability/grafana/provisioning/datasources/prometheus.yml`:
```yaml
apiVersion: 1
datasources:
  - name: Prometheus
    type: prometheus
    uid: prometheus
    url: http://prometheus:9090
    isDefault: true
    editable: false
    jsonData:
      timeInterval: 10s
```

- [ ] **Step 2: Create Grafana dashboard provider**

```bash
mkdir -p /home/guilherme/Systems/Projects/url-shortener/observability/grafana/provisioning/dashboards
```

`observability/grafana/provisioning/dashboards/shortener.yml`:
```yaml
apiVersion: 1
providers:
  - name: shortener
    folder: URL Shortener
    type: file
    disableDeletion: false
    updateIntervalSeconds: 30
    options:
      path: /var/lib/grafana/dashboards
```

- [ ] **Step 3: Create the dashboard JSON**

`observability/grafana/dashboards/shortener.json`:

```json
{
  "annotations": { "list": [] },
  "editable": true,
  "fiscalYearStartMonth": 0,
  "graphTooltip": 1,
  "links": [],
  "panels": [
    {
      "id": 1,
      "type": "timeseries",
      "title": "Requests per Second",
      "gridPos": { "h": 8, "w": 12, "x": 0, "y": 0 },
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "fieldConfig": {
        "defaults": { "unit": "reqps", "custom": { "lineWidth": 2 } },
        "overrides": []
      },
      "options": { "legend": { "displayMode": "list", "placement": "bottom" }, "tooltip": { "mode": "multi" } },
      "targets": [
        {
          "datasource": { "type": "prometheus", "uid": "prometheus" },
          "expr": "sum by (uri) (rate(http_server_requests_seconds_count{application=\"url-shortener\"}[1m]))",
          "legendFormat": "{{ uri }}"
        }
      ]
    },
    {
      "id": 2,
      "type": "timeseries",
      "title": "Redirect Latency p50 / p95 / p99",
      "gridPos": { "h": 8, "w": 12, "x": 12, "y": 0 },
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "fieldConfig": {
        "defaults": { "unit": "s", "custom": { "lineWidth": 2 } },
        "overrides": []
      },
      "options": { "legend": { "displayMode": "list", "placement": "bottom" }, "tooltip": { "mode": "multi" } },
      "targets": [
        {
          "datasource": { "type": "prometheus", "uid": "prometheus" },
          "expr": "histogram_quantile(0.50, sum by (le) (rate(shortener_redirect_latency_seconds_bucket[1m])))",
          "legendFormat": "p50"
        },
        {
          "datasource": { "type": "prometheus", "uid": "prometheus" },
          "expr": "histogram_quantile(0.95, sum by (le) (rate(shortener_redirect_latency_seconds_bucket[1m])))",
          "legendFormat": "p95"
        },
        {
          "datasource": { "type": "prometheus", "uid": "prometheus" },
          "expr": "histogram_quantile(0.99, sum by (le) (rate(shortener_redirect_latency_seconds_bucket[1m])))",
          "legendFormat": "p99"
        }
      ]
    },
    {
      "id": 3,
      "type": "timeseries",
      "title": "Cache Hit Ratio",
      "gridPos": { "h": 8, "w": 12, "x": 0, "y": 8 },
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "fieldConfig": {
        "defaults": {
          "unit": "percentunit",
          "min": 0,
          "max": 1,
          "custom": { "lineWidth": 2 }
        },
        "overrides": []
      },
      "options": { "legend": { "displayMode": "list", "placement": "bottom" }, "tooltip": { "mode": "multi" } },
      "targets": [
        {
          "datasource": { "type": "prometheus", "uid": "prometheus" },
          "expr": "sum(rate(shortener_cache_hits_total[5m])) / (sum(rate(shortener_cache_hits_total[5m])) + sum(rate(shortener_cache_misses_total[5m])))",
          "legendFormat": "hit ratio"
        },
        {
          "datasource": { "type": "prometheus", "uid": "prometheus" },
          "expr": "sum by (type) (rate(shortener_cache_hits_total[5m])) / (sum(rate(shortener_cache_hits_total[5m])) + sum(rate(shortener_cache_misses_total[5m])))",
          "legendFormat": "{{ type }} hit ratio"
        }
      ]
    },
    {
      "id": 4,
      "type": "timeseries",
      "title": "Errors per Second (4xx / 5xx)",
      "gridPos": { "h": 8, "w": 12, "x": 12, "y": 8 },
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "fieldConfig": {
        "defaults": { "unit": "reqps", "custom": { "lineWidth": 2 } },
        "overrides": []
      },
      "options": { "legend": { "displayMode": "list", "placement": "bottom" }, "tooltip": { "mode": "multi" } },
      "targets": [
        {
          "datasource": { "type": "prometheus", "uid": "prometheus" },
          "expr": "sum by (status) (rate(http_server_requests_seconds_count{application=\"url-shortener\",status=~\"4..\"}[1m]))",
          "legendFormat": "4xx {{ status }}"
        },
        {
          "datasource": { "type": "prometheus", "uid": "prometheus" },
          "expr": "sum by (status) (rate(http_server_requests_seconds_count{application=\"url-shortener\",status=~\"5..\"}[1m]))",
          "legendFormat": "5xx {{ status }}"
        }
      ]
    },
    {
      "id": 5,
      "type": "timeseries",
      "title": "RabbitMQ Queue Depth",
      "gridPos": { "h": 8, "w": 8, "x": 0, "y": 16 },
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "fieldConfig": {
        "defaults": { "unit": "short", "custom": { "lineWidth": 2 } },
        "overrides": []
      },
      "options": { "legend": { "displayMode": "list", "placement": "bottom" }, "tooltip": { "mode": "multi" } },
      "targets": [
        {
          "datasource": { "type": "prometheus", "uid": "prometheus" },
          "expr": "rabbitmq_queue_messages_ready{queue=\"url.access.log\"}",
          "legendFormat": "url.access.log ready"
        },
        {
          "datasource": { "type": "prometheus", "uid": "prometheus" },
          "expr": "rabbitmq_queue_messages_ready{queue=\"url.access.log.dlq\"}",
          "legendFormat": "url.access.log.dlq ready"
        }
      ]
    },
    {
      "id": 6,
      "type": "timeseries",
      "title": "JVM Heap Used per Replica",
      "gridPos": { "h": 8, "w": 8, "x": 8, "y": 16 },
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "fieldConfig": {
        "defaults": { "unit": "bytes", "custom": { "lineWidth": 2 } },
        "overrides": []
      },
      "options": { "legend": { "displayMode": "list", "placement": "bottom" }, "tooltip": { "mode": "multi" } },
      "targets": [
        {
          "datasource": { "type": "prometheus", "uid": "prometheus" },
          "expr": "sum by (instance, area) (jvm_memory_used_bytes{application=\"url-shortener\",area=\"heap\"})",
          "legendFormat": "{{ instance }} heap"
        },
        {
          "datasource": { "type": "prometheus", "uid": "prometheus" },
          "expr": "sum by (instance) (rate(jvm_gc_pause_seconds_sum{application=\"url-shortener\"}[1m]))",
          "legendFormat": "{{ instance }} GC pause rate"
        }
      ]
    },
    {
      "id": 7,
      "type": "timeseries",
      "title": "Log Consumer Lag",
      "gridPos": { "h": 8, "w": 8, "x": 16, "y": 16 },
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "fieldConfig": {
        "defaults": { "unit": "s", "custom": { "lineWidth": 2 } },
        "overrides": []
      },
      "options": { "legend": { "displayMode": "list", "placement": "bottom" }, "tooltip": { "mode": "multi" } },
      "targets": [
        {
          "datasource": { "type": "prometheus", "uid": "prometheus" },
          "expr": "shortener_log_consumer_lag_seconds",
          "legendFormat": "{{ instance }} consumer lag"
        }
      ]
    }
  ],
  "refresh": "10s",
  "schemaVersion": 38,
  "tags": ["url-shortener"],
  "templating": { "list": [] },
  "time": { "from": "now-1h", "to": "now" },
  "timepicker": {},
  "timezone": "browser",
  "title": "URL Shortener",
  "uid": "url-shortener-v1",
  "version": 1
}
```

- [ ] **Step 4: Run all tests to confirm nothing broken**

```bash
cd /home/guilherme/Systems/Projects/url-shortener/api && ./gradlew test --no-daemon 2>&1 | tail -15
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add observability/grafana/provisioning/ observability/grafana/dashboards/shortener.json
git commit -m "feat(step-10): Grafana datasource and dashboard provisioning"
```
