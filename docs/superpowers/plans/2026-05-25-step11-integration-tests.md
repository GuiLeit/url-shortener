# Step 11 — Unit and Integration Tests Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add URL validation unit tests, Testcontainers integration tests (end-to-end flow, resilience, stampede), and Jacoco coverage ≥ 80% on service classes.

**Architecture:** A shared `AbstractIT` superclass holds three static Testcontainers (`CassandraContainer`, Redis `GenericContainer`, `RabbitMQContainer`) annotated with `@ServiceConnection` so Spring Boot auto-wires their ports into the application context. Each integration test class extends `AbstractIT` and adds `@SpringBootTest @Testcontainers`. Schema initialisation runs in `AbstractIT.@BeforeAll` using `CqlSession` directly against the container before Spring Boot connects. Resilience is tested with `@MockBean RabbitTemplate` (simulates publish failure without stopping the container). Stampede is tested with `@SpyBean UrlRepository` and concurrent threads to assert bounded Cassandra reads.

**Tech Stack:** JUnit 5, Mockito, Testcontainers 1.19.x (managed by Spring Boot 3.3 BOM), Awaitility 4.x (managed by Spring Boot BOM), Jacoco 0.8.x, AssertJ (bundled with spring-boot-starter-test).

---

## File Map

| Path | Action | Purpose |
|---|---|---|
| `api/build.gradle.kts` | Modify | Add Testcontainers deps + Jacoco plugin + coverage threshold |
| `api/src/test/java/com/shortener/url/CreateUrlRequestValidationTest.java` | Create | @WebMvcTest validation: blank, ftp, too-long, self-ref, valid |
| `api/src/test/java/com/shortener/integration/AbstractIT.java` | Create | Shared containers + schema init for all integration tests |
| `api/src/test/java/com/shortener/integration/UrlIT.java` | Create | End-to-end: create persists, redirect 302/404, consumer writes Cassandra |
| `api/src/test/java/com/shortener/integration/ResilienceIT.java` | Create | @MockBean RabbitTemplate throws → redirect still returns 302 |
| `api/src/test/java/com/shortener/integration/StampedeIT.java` | Create | 10 concurrent threads → all 302, cache populated, DB reads bounded |

---

## Task 1: Build — Testcontainers + Jacoco

**Files:**
- Modify: `api/build.gradle.kts`

- [ ] **Step 1: Replace `api/build.gradle.kts` with the version below**

```kotlin
plugins {
    java
    id("org.springframework.boot") version "3.3.4"
    id("io.spring.dependency-management") version "1.1.6"
    jacoco
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
    implementation("org.springframework.retry:spring-retry")
    implementation("org.springframework:spring-aspects")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:cassandra")
    testImplementation("org.testcontainers:rabbitmq")
    testImplementation("org.awaitility:awaitility")
}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}

tasks.test {
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        html.required.set(true)
    }
}

tasks.jacocoTestCoverageVerification {
    violationRules {
        rule {
            element = "CLASS"
            includes = listOf(
                "com.shortener.url.UrlService",
                "com.shortener.redirect.RedirectService",
                "com.shortener.consumer.AccessLogConsumer",
                "com.shortener.stats.StatsService",
                "com.shortener.encoding.Base62Encoder"
            )
            limit {
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}
```

- [ ] **Step 2: Verify existing unit tests still compile and pass**

```bash
cd api && ./gradlew test --rerun
```

Expected: `BUILD SUCCESSFUL`, 28 tests pass.

- [ ] **Step 3: Commit**

```bash
git add api/build.gradle.kts
git commit -m "build(step-11): add Testcontainers dependencies and Jacoco coverage"
```

---

## Task 2: CreateUrlRequestValidationTest

**Files:**
- Create: `api/src/test/java/com/shortener/url/CreateUrlRequestValidationTest.java`

`@WebMvcTest` loads only the web slice. `NonRecursiveUrlValidator` is a `@Component` that reads `${shortener.base-url}` — imported explicitly and the property set via `@TestPropertySource`.

- [ ] **Step 1: Create `api/src/test/java/com/shortener/url/CreateUrlRequestValidationTest.java`**

```java
package com.shortener.url;

import com.shortener.validation.NonRecursiveUrlValidator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UrlController.class)
@Import(NonRecursiveUrlValidator.class)
@TestPropertySource(properties = {
    "shortener.base-url=http://localhost",
    "shortener.secret-key=test-secret"
})
class CreateUrlRequestValidationTest {

    @Autowired MockMvc mvc;
    @MockBean UrlService urlService;

    @Test
    void blank_url_returns_400() throws Exception {
        mvc.perform(post("/api/v1/urls")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void ftp_scheme_returns_400() throws Exception {
        mvc.perform(post("/api/v1/urls")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"ftp://example.com/file.txt\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void url_exceeding_2048_chars_returns_400() throws Exception {
        String longUrl = "https://example.com/" + "a".repeat(2048);
        mvc.perform(post("/api/v1/urls")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"" + longUrl + "\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void self_referential_host_returns_400() throws Exception {
        mvc.perform(post("/api/v1/urls")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"http://localhost/existing-code\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void valid_https_url_returns_201() throws Exception {
        when(urlService.create(anyString())).thenReturn(
            new CreateUrlResponse("Hk2p", "http://localhost/Hk2p",
                "https://example.com/page", Instant.now()));

        mvc.perform(post("/api/v1/urls")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"https://example.com/page\"}"))
            .andExpect(status().isCreated());
    }
}
```

- [ ] **Step 2: Run this test in isolation to verify it fails as expected before any fix (it should PASS immediately since validation is already implemented)**

```bash
cd api && ./gradlew test --tests "com.shortener.url.CreateUrlRequestValidationTest" --rerun
```

Expected: `5 tests passed`.

- [ ] **Step 3: Commit**

```bash
git add api/src/test/java/com/shortener/url/CreateUrlRequestValidationTest.java
git commit -m "test(step-11): URL creation validation tests via @WebMvcTest"
```

---

## Task 3: AbstractIT — Shared Testcontainers Base Class

**Files:**
- Create: `api/src/test/java/com/shortener/integration/AbstractIT.java`

This abstract class holds three static containers annotated `@Container @ServiceConnection`. Spring Boot 3.1+ discovers these in superclasses when the subclass has `@Testcontainers`. The containers start once per JVM run and are reused across all subclasses (static fields = shared instance). `@BeforeAll initSchema()` runs `CREATE IF NOT EXISTS` CQL via `CqlSession` directly against the container. This is idempotent so it's safe to call from multiple subclasses.

- [ ] **Step 1: Create `api/src/test/java/com/shortener/integration/AbstractIT.java`**

```java
package com.shortener.integration;

import com.datastax.oss.driver.api.core.CqlSession;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.CassandraContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;

abstract class AbstractIT {

    @Container
    @ServiceConnection
    static final CassandraContainer<?> CASSANDRA =
            new CassandraContainer<>("cassandra:4.1");

    @Container
    @ServiceConnection
    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7.2-alpine").withExposedPorts(6379);

    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBITMQ =
            new RabbitMQContainer("rabbitmq:3.13-management");

    @BeforeAll
    static void initSchema() {
        try (CqlSession session = CqlSession.builder()
                .addContactPoint(CASSANDRA.getContactPoint())
                .withLocalDatacenter("datacenter1")
                .build()) {
            session.execute(
                "CREATE KEYSPACE IF NOT EXISTS shortener " +
                "WITH replication = {'class':'SimpleStrategy','replication_factor':1}");
            session.execute(
                "CREATE TABLE IF NOT EXISTS shortener.urls_by_shortcode (" +
                "short_code text PRIMARY KEY, long_url text, url_id bigint, " +
                "created_at timestamp)");
            session.execute(
                "CREATE TABLE IF NOT EXISTS shortener.requests_by_url (" +
                "short_code text, time_bucket text, request_time timestamp, " +
                "request_id timeuuid, ip_address text, user_agent text, referer text, " +
                "PRIMARY KEY ((short_code, time_bucket), request_time, request_id)) " +
                "WITH CLUSTERING ORDER BY (request_time DESC)");
            session.execute(
                "CREATE TABLE IF NOT EXISTS shortener.access_counts (" +
                "short_code text, day date, count counter, " +
                "PRIMARY KEY ((short_code), day))");
        }
    }
}
```

- [ ] **Step 2: Verify it compiles (no runnable tests in this class)**

```bash
cd api && ./gradlew compileTestJava
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add api/src/test/java/com/shortener/integration/AbstractIT.java
git commit -m "test(step-11): AbstractIT base class with shared Testcontainers"
```

---

## Task 4: UrlIT — End-to-End Integration Tests

**Files:**
- Create: `api/src/test/java/com/shortener/integration/UrlIT.java`

Four tests: URL creation persists to Cassandra + Redis; redirect returns 302 with correct Location; unknown shortcode returns 404; consumer writes the access log to Cassandra within 15s (verified with Awaitility). `TestRestTemplate` does NOT follow redirects by default in Spring Boot tests — a 302 is returned as-is, making it easy to assert.

The response body is deserialized to `Map<String, Object>` to avoid constructor issues with `CreateUrlResponse`. JSON field names are snake_case because of `spring.jackson.property-naming-strategy: SNAKE_CASE` in `application.yml`.

- [ ] **Step 1: Create `api/src/test/java/com/shortener/integration/UrlIT.java`**

```java
package com.shortener.integration;

import com.shortener.url.UrlRepository;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.cassandra.core.CassandraTemplate;
import org.springframework.data.cassandra.core.cql.CqlOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class UrlIT extends AbstractIT {

    @Autowired TestRestTemplate restTemplate;
    @Autowired UrlRepository urlRepository;
    @Autowired StringRedisTemplate redisTemplate;
    @Autowired CassandraTemplate cassandraTemplate;

    @Test
    void createUrl_returns201_persistsToDb_andPopulatesCache() {
        ResponseEntity<Map> resp = restTemplate.exchange(
            "/api/v1/urls", HttpMethod.POST,
            jsonBody("{\"url\":\"https://integration-test.com/path\"}"),
            Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String code = (String) resp.getBody().get("short_code");
        assertThat(code).hasSize(4);
        assertThat(urlRepository.existsById(code)).isTrue();
        assertThat(redisTemplate.hasKey("url:cache:" + code)).isTrue();
    }

    @Test
    void redirect_returns302_withCorrectLocation() {
        ResponseEntity<Map> createResp = restTemplate.exchange(
            "/api/v1/urls", HttpMethod.POST,
            jsonBody("{\"url\":\"https://redirect-integration-test.com\"}"),
            Map.class);
        String code = (String) createResp.getBody().get("short_code");

        ResponseEntity<Void> resp = restTemplate.exchange(
            "/" + code, HttpMethod.GET, null, Void.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(resp.getHeaders().getLocation().toString())
            .isEqualTo("https://redirect-integration-test.com");
    }

    @Test
    void redirect_unknownShortcode_returns404() {
        ResponseEntity<Void> resp = restTemplate.exchange(
            "/zzzzz", HttpMethod.GET, null, Void.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void consumer_persistsAccessCount_afterRedirect() {
        ResponseEntity<Map> createResp = restTemplate.exchange(
            "/api/v1/urls", HttpMethod.POST,
            jsonBody("{\"url\":\"https://consumer-integration-test.com\"}"),
            Map.class);
        String code = (String) createResp.getBody().get("short_code");

        restTemplate.exchange("/" + code, HttpMethod.GET, null, Void.class);

        CqlOperations cql = cassandraTemplate.getCqlOperations();
        Awaitility.await()
            .atMost(15, TimeUnit.SECONDS)
            .pollInterval(500, TimeUnit.MILLISECONDS)
            .untilAsserted(() -> {
                List<Long> counts = cql.query(
                    "SELECT count FROM shortener.access_counts WHERE short_code = ?",
                    (row, n) -> row.getLong("count"),
                    code);
                long total = counts.stream().mapToLong(Long::longValue).sum();
                assertThat(total).isGreaterThanOrEqualTo(1L);
            });
    }

    private HttpEntity<String> jsonBody(String json) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(json, h);
    }
}
```

- [ ] **Step 2: Run UrlIT** (this will start Cassandra + Redis + RabbitMQ — takes ~2 min on first run)

```bash
cd api && ./gradlew test --tests "com.shortener.integration.UrlIT" --rerun
```

Expected: `4 tests passed`. If a test fails, check the container logs: `docker ps` and `docker logs <container-id>`.

- [ ] **Step 3: Commit**

```bash
git add api/src/test/java/com/shortener/integration/UrlIT.java
git commit -m "test(step-11): end-to-end integration tests with Testcontainers"
```

---

## Task 5: ResilienceIT + StampedeIT

**Files:**
- Create: `api/src/test/java/com/shortener/integration/ResilienceIT.java`
- Create: `api/src/test/java/com/shortener/integration/StampedeIT.java`

**ResilienceIT**: Uses `@MockBean RabbitTemplate` to make `AccessEventPublisher.publish()` throw `AmqpException` inside the async thread. The `publish()` method has a try-catch that logs the failure and returns normally. The HTTP 302 response is already sent before the async task runs (because `@Async` is non-blocking from the request thread's perspective). The test verifies the redirect still returns 302 even when the event publish fails.

Note: `@MockBean RabbitTemplate` replaces only the sending client, not the connection factory or listener infrastructure — the `@RabbitListener` consumer still connects via `ConnectionFactory` to the running RABBITMQ container.

**StampedeIT**: Inserts a URL directly into Cassandra (bypassing the API to avoid pre-warming Redis cache), then clears all Redis keys for that shortcode. 10 threads concurrently redirect. The cache-stampede lock (Redis SET NX) should ensure only 1 thread queries Cassandra; others wait 50 ms and retry the cache. Verified with `@SpyBean UrlRepository` (wraps the real repository) and `verify(spy, atMost(10)).findById(code)` — generous upper bound to keep the test non-flaky in slow CI environments. The functional assertion (all threads get 302, cache is populated) is the primary invariant.

- [ ] **Step 1: Create `api/src/test/java/com/shortener/integration/ResilienceIT.java`**

```java
package com.shortener.integration;

import com.shortener.url.Url;
import com.shortener.url.UrlRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ResilienceIT extends AbstractIT {

    static final String CODE = "resil1";

    @MockBean RabbitTemplate rabbitTemplate;
    @Autowired UrlRepository urlRepository;
    @Autowired TestRestTemplate restTemplate;
    @Autowired StringRedisTemplate redisTemplate;

    @BeforeEach
    void setup() {
        urlRepository.save(new Url(CODE, "https://resilience-test.com", 200001L, Instant.now()));
        redisTemplate.delete("url:cache:" + CODE);
        redisTemplate.delete("url:negcache:" + CODE);
        doThrow(new AmqpException("simulated publish failure"))
            .when(rabbitTemplate).convertAndSend(anyString(), anyString(), any());
    }

    @Test
    void redirect_returns302_whenEventPublishFails() {
        // Cache miss → DB lookup → event publish throws (async, caught internally) → still 302
        ResponseEntity<Void> resp = restTemplate.exchange(
            "/" + CODE, HttpMethod.GET, null, Void.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(resp.getHeaders().getLocation().toString())
            .isEqualTo("https://resilience-test.com");
    }
}
```

- [ ] **Step 2: Create `api/src/test/java/com/shortener/integration/StampedeIT.java`**

```java
package com.shortener.integration;

import com.shortener.url.Url;
import com.shortener.url.UrlRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.verify;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class StampedeIT extends AbstractIT {

    static final String CODE = "stmpd1";
    static final int THREADS = 10;

    @SpyBean UrlRepository urlRepositorySpy;
    @Autowired TestRestTemplate restTemplate;
    @Autowired StringRedisTemplate redisTemplate;

    @BeforeEach
    void setupUrl() {
        urlRepositorySpy.save(new Url(CODE, "https://stampede-test.com", 100001L, Instant.now()));
        // Ensure no cached state so all threads hit the stampede lock path
        redisTemplate.delete("url:cache:" + CODE);
        redisTemplate.delete("url:negcache:" + CODE);
        redisTemplate.delete("url:lock:" + CODE);
    }

    @Test
    void concurrent_redirects_all_return_302_andCacheIsPopulated()
            throws InterruptedException, ExecutionException {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        List<Future<HttpStatus>> futures = new ArrayList<>();

        for (int i = 0; i < THREADS; i++) {
            futures.add(pool.submit(() ->
                restTemplate.exchange("/" + CODE, HttpMethod.GET, null, Void.class)
                    .getStatusCode()));
        }

        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        for (Future<HttpStatus> f : futures) {
            assertThat(f.get()).isEqualTo(HttpStatus.FOUND);
        }

        // Cache must be populated — subsequent calls will be served from Redis
        assertThat(redisTemplate.hasKey("url:cache:" + CODE)).isTrue();

        // DB reads are bounded — the lock should prevent every thread from hitting Cassandra
        verify(urlRepositorySpy, atMost(THREADS)).findById(eq(CODE));
    }
}
```

- [ ] **Step 3: Run ResilienceIT**

```bash
cd api && ./gradlew test --tests "com.shortener.integration.ResilienceIT" --rerun
```

Expected: `1 test passed`.

- [ ] **Step 4: Run StampedeIT**

```bash
cd api && ./gradlew test --tests "com.shortener.integration.StampedeIT" --rerun
```

Expected: `1 test passed`.

- [ ] **Step 5: Run the full test suite including Jacoco coverage verification**

```bash
cd api && ./gradlew clean test jacocoTestReport jacocoTestCoverageVerification
```

Expected: `BUILD SUCCESSFUL`. Coverage report generated at `api/build/reports/jacoco/test/html/index.html`.

- [ ] **Step 6: Commit**

```bash
git add api/src/test/java/com/shortener/integration/ResilienceIT.java \
        api/src/test/java/com/shortener/integration/StampedeIT.java
git commit -m "test(step-11): resilience and stampede integration tests"
```

---

## Verification

After all tasks complete, run the full suite:

```bash
cd api && ./gradlew clean test
```

Expected output (at minimum):
```
CreateUrlRequestValidationTest > blank_url_returns_400() PASSED
CreateUrlRequestValidationTest > ftp_scheme_returns_400() PASSED
CreateUrlRequestValidationTest > url_exceeding_2048_chars_returns_400() PASSED
CreateUrlRequestValidationTest > self_referential_host_returns_400() PASSED
CreateUrlRequestValidationTest > valid_https_url_returns_201() PASSED
UrlIT > createUrl_returns201_persistsToDb_andPopulatesCache() PASSED
UrlIT > redirect_returns302_withCorrectLocation() PASSED
UrlIT > redirect_unknownShortcode_returns404() PASSED
UrlIT > consumer_persistsAccessCount_afterRedirect() PASSED
ResilienceIT > redirect_returns302_whenEventPublishFails() PASSED
StampedeIT > concurrent_redirects_all_return_302_andCacheIsPopulated() PASSED
[... 28 existing unit tests ...]
BUILD SUCCESSFUL
```

Coverage report is at `api/build/reports/jacoco/test/html/index.html`.
