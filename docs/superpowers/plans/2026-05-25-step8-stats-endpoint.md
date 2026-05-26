# Step 8 — Stats Endpoint Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement `GET /api/v1/urls/{shortcode}/stats?from=...&to=...` that returns `total_count` (sum of `access_counts` rows) and `recent_requests` (last 100 from `requests_by_url`).

**Architecture:** `StatsController` validates and delegates to `StatsService`. The service uses `UrlRepository.existsById()` for the 404 guard and `CqlOperations` (already a Spring bean from `spring-boot-starter-data-cassandra`) for both raw CQL queries: one range scan on `access_counts`, and a per-bucket iteration on `requests_by_url` (newest-first until 100 collected). `GlobalExceptionHandler` gains an `IllegalArgumentException` → 400 handler for the 90-day cap.

**Tech Stack:** Spring MVC, Spring Data Cassandra `CqlOperations` (raw CQL + RowMapper), `@DateTimeFormat(iso=ISO.DATE)` for LocalDate query params.

**Gradle commands:** All `./gradlew` commands must run from the `api/` subdirectory: `./gradlew -p api ...`
`JAVA_HOME` is already configured in the environment. If running into issues, prefix: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew -p api ...`

---

### Task 1: Response DTOs

**Files:**
- Create: `api/src/main/java/com/shortener/stats/StatsResponse.java`
- Create: `api/src/main/java/com/shortener/stats/RecentRequest.java`

Jackson `SNAKE_CASE` is configured globally (`spring.jackson.property-naming-strategy: SNAKE_CASE` in `application.yml`), so `shortCode` → `short_code`, `totalCount` → `total_count`, etc.

- [ ] **Step 1: Write failing compile test**

```java
// api/src/test/java/com/shortener/stats/StatsDtoTest.java
package com.shortener.stats;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class StatsDtoTest {

    @Test
    void stats_response_stores_fields() {
        RecentRequest req = new RecentRequest(Instant.parse("2026-05-25T10:00:00Z"), "1.2.3.4", "ua", "ref");
        StatsResponse resp = new StatsResponse("abc1", 42L, List.of(req));
        assertEquals("abc1", resp.getShortCode());
        assertEquals(42L, resp.getTotalCount());
        assertEquals(1, resp.getRecentRequests().size());
        assertEquals("1.2.3.4", resp.getRecentRequests().get(0).getIpAddress());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew -p api test --tests "com.shortener.stats.StatsDtoTest" 2>&1
```
Expected: FAIL — class not found

- [ ] **Step 3: Create RecentRequest**

```java
// api/src/main/java/com/shortener/stats/RecentRequest.java
package com.shortener.stats;

import java.time.Instant;

public class RecentRequest {

    private final Instant requestTime;
    private final String ipAddress;
    private final String userAgent;
    private final String referer;

    public RecentRequest(Instant requestTime, String ipAddress, String userAgent, String referer) {
        this.requestTime = requestTime;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
        this.referer = referer;
    }

    public Instant getRequestTime() { return requestTime; }
    public String getIpAddress()    { return ipAddress; }
    public String getUserAgent()    { return userAgent; }
    public String getReferer()      { return referer; }
}
```

- [ ] **Step 4: Create StatsResponse**

```java
// api/src/main/java/com/shortener/stats/StatsResponse.java
package com.shortener.stats;

import java.util.List;

public class StatsResponse {

    private final String shortCode;
    private final long totalCount;
    private final List<RecentRequest> recentRequests;

    public StatsResponse(String shortCode, long totalCount, List<RecentRequest> recentRequests) {
        this.shortCode = shortCode;
        this.totalCount = totalCount;
        this.recentRequests = recentRequests;
    }

    public String getShortCode()                  { return shortCode; }
    public long getTotalCount()                   { return totalCount; }
    public List<RecentRequest> getRecentRequests() { return recentRequests; }
}
```

- [ ] **Step 5: Run test to verify it passes**

```bash
./gradlew -p api test --tests "com.shortener.stats.StatsDtoTest" 2>&1
```
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add api/src/main/java/com/shortener/stats/RecentRequest.java \
        api/src/main/java/com/shortener/stats/StatsResponse.java \
        api/src/test/java/com/shortener/stats/StatsDtoTest.java
git commit -m "feat(step-8): StatsResponse and RecentRequest DTOs"
```

---

### Task 2: StatsService with unit tests

**Files:**
- Create: `api/src/main/java/com/shortener/stats/StatsService.java`
- Create: `api/src/test/java/com/shortener/stats/StatsServiceTest.java`

**Key design decisions:**
- Inject `UrlRepository` (exists from Step 5) for existence check via `existsById(shortCode)`
- Inject `CqlOperations` — Spring Data Cassandra auto-configures a `CqlTemplate` bean implementing this interface
- `total_count`: single parameterized CQL on `access_counts`, sum all returned counts
- `recent_requests`: iterate hourly buckets from newest to oldest (descending), collecting up to 100 total
  - Format: `"yyyy-MM-dd-HH"` UTC (matches Step 7 consumer bucket format)
  - At most 90 days × 24 hours = 2160 buckets, but stops as soon as 100 entries are collected

**`CqlOperations.query` signature:**
```java
<T> List<T> query(String cql, RowMapper<T> rowMapper, Object... args)
```
`RowMapper<T>` is `org.springframework.data.cassandra.core.cql.RowMapper<T>` (functional interface: `T mapRow(Row row, int rowNum)`).
`Row` is `com.datastax.oss.driver.api.core.cql.Row`.

**Validation rules:**
- Shortcode not found → throw `com.shortener.web.NotFoundException`
- `ChronoUnit.DAYS.between(from, to) > 90` → throw `IllegalArgumentException("Date range cannot exceed 90 days")`

- [ ] **Step 1: Write failing tests**

```java
// api/src/test/java/com/shortener/stats/StatsServiceTest.java
package com.shortener.stats;

import com.shortener.url.UrlRepository;
import com.shortener.web.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.cassandra.core.cql.CqlOperations;
import org.springframework.data.cassandra.core.cql.RowMapper;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StatsServiceTest {

    @Mock UrlRepository urlRepository;
    @Mock CqlOperations cqlOperations;

    StatsService statsService;

    @BeforeEach
    void setUp() {
        statsService = new StatsService(urlRepository, cqlOperations);
    }

    @Test
    void shortcode_not_found_throws_not_found() {
        when(urlRepository.existsById("missing")).thenReturn(false);
        assertThrows(NotFoundException.class, () ->
            statsService.getStats("missing", LocalDate.of(2026, 5, 18), LocalDate.of(2026, 5, 25)));
    }

    @Test
    void date_range_over_90_days_throws_illegal_argument() {
        when(urlRepository.existsById("abc1")).thenReturn(true);
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2026, 5, 25); // > 90 days
        assertThrows(IllegalArgumentException.class, () ->
            statsService.getStats("abc1", from, to));
    }

    @Test
    void total_count_is_sum_of_access_count_rows() {
        LocalDate from = LocalDate.of(2026, 5, 18);
        LocalDate to = LocalDate.of(2026, 5, 25);
        when(urlRepository.existsById("abc1")).thenReturn(true);
        doAnswer(inv -> {
            String cql = inv.getArgument(0);
            return cql.contains("access_counts") ? List.of(100L, 50L) : List.of();
        }).when(cqlOperations).query(anyString(), any(RowMapper.class), any());

        StatsResponse resp = statsService.getStats("abc1", from, to);
        assertEquals(150L, resp.getTotalCount());
    }

    @Test
    void recent_requests_returned_from_requests_by_url() {
        LocalDate from = LocalDate.of(2026, 5, 25);
        LocalDate to = LocalDate.of(2026, 5, 25);
        when(urlRepository.existsById("abc1")).thenReturn(true);
        RecentRequest req = new RecentRequest(java.time.Instant.now(), "1.2.3.4", "ua", null);
        doAnswer(inv -> {
            String cql = inv.getArgument(0);
            return cql.contains("requests_by_url") ? List.of(req) : List.of();
        }).when(cqlOperations).query(anyString(), any(RowMapper.class), any());

        StatsResponse resp = statsService.getStats("abc1", from, to);
        assertEquals(1, resp.getRecentRequests().size());
        assertEquals("1.2.3.4", resp.getRecentRequests().get(0).getIpAddress());
    }
}
```

**Important Mockito note on varargs:** `any()` as the last matcher in `doAnswer(...).when(cqlOperations).query(anyString(), any(RowMapper.class), any())` covers the `Object... args` vararg parameter. If Mockito strict mode complains, use `lenient().doAnswer(...)` or add `@SuppressWarnings("unchecked")` to the test methods.

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew -p api test --tests "com.shortener.stats.StatsServiceTest" 2>&1
```
Expected: FAIL — class not found

- [ ] **Step 3: Create StatsService**

```java
// api/src/main/java/com/shortener/stats/StatsService.java
package com.shortener.stats;

import com.shortener.url.UrlRepository;
import com.shortener.web.NotFoundException;
import org.springframework.data.cassandra.core.cql.CqlOperations;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

@Service
public class StatsService {

    private static final DateTimeFormatter BUCKET_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HH").withZone(ZoneOffset.UTC);
    private static final int MAX_RECENT = 100;

    private final UrlRepository urlRepository;
    private final CqlOperations cqlOperations;

    public StatsService(UrlRepository urlRepository, CqlOperations cqlOperations) {
        this.urlRepository = urlRepository;
        this.cqlOperations = cqlOperations;
    }

    public StatsResponse getStats(String shortCode, LocalDate from, LocalDate to) {
        if (!urlRepository.existsById(shortCode)) {
            throw new NotFoundException("Short code not found: " + shortCode);
        }
        if (ChronoUnit.DAYS.between(from, to) > 90) {
            throw new IllegalArgumentException("Date range cannot exceed 90 days");
        }

        long totalCount = cqlOperations.query(
                "SELECT count FROM shortener.access_counts WHERE short_code = ? AND day >= ? AND day <= ?",
                (row, n) -> row.getLong("count"),
                shortCode, from, to
        ).stream().mapToLong(Long::longValue).sum();

        List<RecentRequest> recentRequests = queryRecentRequests(shortCode, from, to);

        return new StatsResponse(shortCode, totalCount, recentRequests);
    }

    private List<RecentRequest> queryRecentRequests(String shortCode, LocalDate from, LocalDate to) {
        List<RecentRequest> results = new ArrayList<>();
        LocalDate current = to;
        while (!current.isBefore(from) && results.size() < MAX_RECENT) {
            for (int h = 23; h >= 0 && results.size() < MAX_RECENT; h--) {
                String bucket = current + "-" + String.format("%02d", h);
                int remaining = MAX_RECENT - results.size();
                List<RecentRequest> partial = cqlOperations.query(
                        "SELECT request_time, ip_address, user_agent, referer FROM shortener.requests_by_url WHERE short_code = ? AND time_bucket = ? LIMIT ?",
                        (row, n) -> new RecentRequest(
                                row.get("request_time", Instant.class),
                                row.getString("ip_address"),
                                row.getString("user_agent"),
                                row.getString("referer")),
                        shortCode, bucket, remaining);
                results.addAll(partial);
            }
            current = current.minusDays(1);
        }
        return results;
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew -p api test --tests "com.shortener.stats.StatsServiceTest" 2>&1
```
Expected: 4/4 PASS

- [ ] **Step 5: Run all tests**

```bash
./gradlew -p api test 2>&1
```
Expected: All tests pass

- [ ] **Step 6: Commit**

```bash
git add api/src/main/java/com/shortener/stats/StatsService.java \
        api/src/test/java/com/shortener/stats/StatsServiceTest.java
git commit -m "feat(step-8): StatsService with access counts and recent requests queries"
```

---

### Task 3: StatsController and GlobalExceptionHandler update

**Files:**
- Create: `api/src/main/java/com/shortener/stats/StatsController.java`
- Modify: `api/src/main/java/com/shortener/web/GlobalExceptionHandler.java`

**Controller spec:**
- `@RestController`, `@RequestMapping("/api/v1/urls")`, `@Validated`
- `GET /{shortcode}/stats` with:
  - `@PathVariable @Pattern(regexp = "[A-Za-z0-9]{1,11}") String shortcode`
  - `@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from`
  - `@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to`
- Resolve defaults: `to = now(UTC) if null`, `from = to.minusDays(7) if null`
- Returns `StatsResponse` directly (200)

**GlobalExceptionHandler addition:**
```java
@ExceptionHandler(IllegalArgumentException.class)
@ResponseStatus(HttpStatus.BAD_REQUEST)
public ErrorResponse handleBadRequest(IllegalArgumentException ex) {
    return new ErrorResponse(ex.getMessage(), List.of());
}
```

- [ ] **Step 1: Write failing test for controller**

```java
// api/src/test/java/com/shortener/stats/StatsControllerTest.java
package com.shortener.stats;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class StatsControllerTest {
    @Test
    void controller_class_exists() {
        assertNotNull(StatsController.class);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew -p api test --tests "com.shortener.stats.StatsControllerTest" 2>&1
```
Expected: FAIL

- [ ] **Step 3: Create StatsController**

```java
// api/src/main/java/com/shortener/stats/StatsController.java
package com.shortener.stats;

import jakarta.validation.constraints.Pattern;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.ZoneOffset;

@RestController
@RequestMapping("/api/v1/urls")
@Validated
public class StatsController {

    private final StatsService statsService;

    public StatsController(StatsService statsService) {
        this.statsService = statsService;
    }

    @GetMapping("/{shortcode}/stats")
    public StatsResponse getStats(
            @PathVariable @Pattern(regexp = "[A-Za-z0-9]{1,11}") String shortcode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        LocalDate resolvedTo = to != null ? to : LocalDate.now(ZoneOffset.UTC);
        LocalDate resolvedFrom = from != null ? from : resolvedTo.minusDays(7);
        return statsService.getStats(shortcode, resolvedFrom, resolvedTo);
    }
}
```

- [ ] **Step 4: Add IllegalArgumentException handler to GlobalExceptionHandler**

Current file is at `api/src/main/java/com/shortener/web/GlobalExceptionHandler.java`. Add this method inside the class body (after the existing `handleConstraintViolation` method):

```java
@ExceptionHandler(IllegalArgumentException.class)
@ResponseStatus(HttpStatus.BAD_REQUEST)
public ErrorResponse handleBadRequest(IllegalArgumentException ex) {
    return new ErrorResponse(ex.getMessage(), List.of());
}
```

- [ ] **Step 5: Run all tests**

```bash
./gradlew -p api test 2>&1
```
Expected: All tests pass (currently ~16 tests + new ones)

- [ ] **Step 6: Commit**

```bash
git add api/src/main/java/com/shortener/stats/StatsController.java \
        api/src/main/java/com/shortener/web/GlobalExceptionHandler.java \
        api/src/test/java/com/shortener/stats/StatsControllerTest.java
git commit -m "feat(step-8): StatsController and IllegalArgumentException handler"
```
