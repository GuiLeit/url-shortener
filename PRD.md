# URL Shortener — Product Requirements Document (PRD)

> **Audience**: AI coding agent (Claude Code, Cursor, etc.).
> **Goal**: A portfolio-grade URL shortener demonstrating distributed-systems patterns: load balancing, caching, NoSQL time-series storage, async messaging, observability, and load testing.
> **How to use this document**: Execute the steps in `Section 8` in order. Do **not** skip ahead. After every step, run the listed verification commands and confirm the *Definition of Done* before moving on.

---

## 1. Project Overview

A backend service that converts long URLs into short codes and redirects shortcode requests to the original URL. The system records every redirect for analytics. There is **no user authentication** — this is a stateless public service protected by Nginx-level rate limiting.

**Why each component exists** (preserve these justifications in `README.md` and `DECISIONS.md`):

| Component | Purpose |
|---|---|
| Nginx | Load balancing across Spring replicas + L7 rate limiting |
| Spring Boot (Java 21) × 3 replicas | Stateless API handling create/redirect/stats |
| Redis | (a) Atomic `INCR` for ID generation, (b) cache for hot shortcodes |
| Cassandra | Persistent storage for URLs and access logs (write-heavy time-series) |
| RabbitMQ | Decouple log writes from the redirect hot path |
| Prometheus + Grafana | Metrics scraping and dashboards |
| Docker Compose | Orchestration for local dev and single-VPS deployment |

---

## 2. Architecture

```
                     ┌──────────────────┐
                     │   Client / curl  │
                     └────────┬─────────┘
                              │
                              ▼
                     ┌──────────────────┐
                     │  Nginx (rate     │
                     │  limit + LB)     │
                     └────────┬─────────┘
              ┌───────────────┼───────────────┐
              ▼               ▼               ▼
         ┌─────────┐     ┌─────────┐     ┌─────────┐
         │ Spring1 │     │ Spring2 │     │ Spring3 │
         └────┬────┘     └────┬────┘     └────┬────┘
              │               │               │
   ┌──────────┴───────────────┴───────────────┴──────────┐
   │                                                     │
   ▼                                                     ▼
┌─────────┐   ┌────────────┐                      ┌────────────┐
│  Redis  │   │  RabbitMQ  │ ─► log consumer ───► │ Cassandra  │
│ (INCR + │   │ (url.events│   (same JAR, async   │ (urls +    │
│  cache) │   │  exchange) │    listener)         │  requests) │
└─────────┘   └────────────┘                      └────────────┘

Observability: Prometheus scrapes /actuator/prometheus from every Spring replica
               → Grafana queries Prometheus.
```

**Hot path (redirect)**: Nginx → Spring → Redis (cache hit) → 302. On miss: Spring → Cassandra → populate Redis → 302. Logging is published to RabbitMQ in fire-and-forget mode and **never** blocks the redirect response.

**Cold path (creation)**: Nginx → Spring → Redis `INCR` → Base62 encode → Cassandra insert → return shortcode.

---

## 3. Tech Stack (fixed versions)

The agent must use these versions. If a compatibility issue arises, document the deviation in `DECISIONS.md`.

- Java 21 (Eclipse Temurin)
- Spring Boot 3.3.x
- Spring Web, Spring Data Cassandra, Spring Data Redis (Lettuce), Spring AMQP, Spring Boot Actuator, Micrometer Prometheus, Spring Validation
- Cassandra 4.1
- Redis 7.2
- RabbitMQ 3.13 (with management plugin)
- Nginx 1.27 (alpine)
- Prometheus latest stable, Grafana latest stable
- Docker Compose v2 (`docker compose`, no hyphen)
- Build tool: Gradle (Kotlin DSL)
- Testing: JUnit 5, Mockito, Testcontainers, k6 for load tests

---

## 4. Functional Requirements

| ID | Requirement | Acceptance |
|---|---|---|
| F1 | `POST /api/v1/urls` accepts `{ "url": "https://..." }` and returns `{ "short_code", "short_url", "long_url", "created_at" }` | 201 on success; 400 on invalid URL |
| F2 | `GET /{shortcode}` returns HTTP 302 with `Location` header set to the original URL | 302 on hit; 404 on miss |
| F3 | Every successful redirect publishes an event to RabbitMQ with: shortcode, timestamp (UTC), client IP (from `X-Forwarded-For`), User-Agent, Referer | Event consumed and persisted to Cassandra within 5s p99 |
| F4 | `GET /api/v1/urls/{shortcode}/stats?from=...&to=...` returns total count and recent events (last 100) | 200 with JSON; 404 if shortcode unknown |

**Out of scope**: user accounts, custom shortcodes, expiration, password-protected links, QR codes. Do not implement these.

---

## 5. Non-Functional Requirements

- **Throughput**: at least 10,000 URL creations/day and 200,000 redirects/day on the test VPS (read:write = 20:1).
- **Latency** (end-to-end, measured at Nginx):
    - Redirect p99 < 100 ms (cache hit), < 250 ms (cache miss)
    - Creation p99 < 300 ms
- **Shortcode rules**: Base62 alphabet (`0-9A-Za-z`), shuffled deterministically via `SHORTENER_SECRET_KEY` env var.
- **Minimum shortcode length**: 4 characters at launch (achieved by initializing `url:counter` to 250000 — see §6.2).
- **Cache TTL**: 7 days on `url:cache:{shortcode}`.
- **Rate limit** (enforced at Nginx):
    - `POST /api/v1/urls`: 10 req/s per IP, burst 20
    - `GET /{shortcode}`: 100 req/s per IP, burst 200
    - `GET /api/v1/urls/*/stats`: 5 req/s per IP, burst 10
- **Statelessness**: any Spring replica can be killed at any time without data loss. Integration tests must prove this.

---

## 6. Data Models

### 6.1 Cassandra schema

Cassandra is modeled by query pattern, not by entity. Create exactly these tables in `cassandra/init.cql`:

```cql
CREATE KEYSPACE IF NOT EXISTS shortener
  WITH replication = { 'class': 'SimpleStrategy', 'replication_factor': 1 };

-- Redirect hot path
CREATE TABLE IF NOT EXISTS shortener.urls_by_shortcode (
    short_code text PRIMARY KEY,
    long_url   text,
    url_id     bigint,
    created_at timestamp
);

-- Access log, partitioned by (shortcode, hour-bucket) to avoid hot partitions
CREATE TABLE IF NOT EXISTS shortener.requests_by_url (
    short_code   text,
    time_bucket  text,        -- format: yyyy-MM-dd-HH (UTC)
    request_time timestamp,
    request_id   timeuuid,
    ip_address   text,
    user_agent   text,
    referer      text,
    PRIMARY KEY ((short_code, time_bucket), request_time, request_id)
) WITH CLUSTERING ORDER BY (request_time DESC);

-- Aggregate counter per shortcode per day (updated by consumer)
CREATE TABLE IF NOT EXISTS shortener.access_counts (
    short_code text,
    day        date,
    count      counter,
    PRIMARY KEY ((short_code), day)
);
```

### 6.2 Redis keys

| Key | Type | Purpose | TTL |
|---|---|---|---|
| `url:counter` | string (int) | INCR target. **Initialize to 250000** before first run. | none |
| `url:cache:{shortcode}` | string | Cached long URL | 7 days |
| `url:negcache:{shortcode}` | string `"0"` | Negative cache: confirmed not found | 60 s |
| `url:lock:{shortcode}` | string | Cache-stampede mutex | 5 s |

The negative cache prevents repeated Cassandra lookups against scraping/scanning traffic.

### 6.3 RabbitMQ topology

- Exchange: `url.events` (topic, durable)
- Queue: `url.access.log` (durable), bound with routing key `url.accessed`
- Dead-letter exchange: `url.events.dlx` → queue `url.access.log.dlq` for messages that fail after 3 retries

Message payload (JSON):
```json
{
  "short_code": "Hk2p",
  "request_time": "2026-05-22T14:33:21.456Z",
  "ip_address": "203.0.113.45",
  "user_agent": "Mozilla/5.0 ...",
  "referer": "https://example.com/page"
}
```

---

## 7. API Specification

### `POST /api/v1/urls`
Request:
```json
{ "url": "https://example.com/some/long/path?q=1" }
```
Validation: must start with `http://` or `https://`, max 2048 chars, must parse as a valid URI. Reject URLs whose host matches the shortener's own host (no recursive shortening).

Response 201:
```json
{
  "short_code": "Hk2p",
  "short_url": "http://localhost/Hk2p",
  "long_url": "https://example.com/some/long/path?q=1",
  "created_at": "2026-05-22T14:33:21Z"
}
```

### `GET /{shortcode}`
- 302 with `Location: <long_url>` and `Cache-Control: no-store` on hit
- 404 with JSON error body on miss
- Publishes access event to RabbitMQ **only on 302**

### `GET /api/v1/urls/{shortcode}/stats`
Query params: `from` (ISO date), `to` (ISO date), both optional (default: last 7 days).

Response 200:
```json
{
  "short_code": "Hk2p",
  "total_count": 1543,
  "recent_requests": [
    { "request_time": "...", "ip_address": "...", "user_agent": "...", "referer": "..." }
  ]
}
```

### `GET /actuator/health`, `GET /actuator/prometheus`
Standard Spring Boot Actuator endpoints. `/actuator/prometheus` must be exposed for scraping.

---

## 8. Implementation Plan (Step-by-Step)

> Each step has: **Goal**, **Tasks**, **Verification**, **Definition of Done**. Do not move on until DoD is satisfied.

### Step 1 — Repository Bootstrap

**Goal**: Create a clean repo structure.

**Tasks**:
1. Initialize a git repo.
2. Create this layout:
   ```
   url-shortener/
   ├── api/
   │   ├── build.gradle.kts
   │   ├── settings.gradle.kts
   │   └── src/{main,test}/java/...
   ├── nginx/
   │   └── nginx.conf
   ├── cassandra/
   │   └── init.cql
   ├── observability/
   │   ├── prometheus.yml
   │   └── grafana/
   │       ├── provisioning/
   │       └── dashboards/
   ├── load-tests/
   │   └── k6/
   ├── docker-compose.yml
   ├── .env.example
   ├── README.md
   └── DECISIONS.md
   ```
3. Create `.gitignore` covering `build/`, `.gradle/`, `*.iml`, `.idea/`, `.env`, `out/`.
4. Create `.env.example` with placeholders: `SHORTENER_SECRET_KEY`, `SPRING_PROFILES_ACTIVE`, `BASE_URL`, plus DB connection details.

**Verification**: `tree -L 3` matches structure above.

**DoD**: Directory tree created; `.gitignore` and `.env.example` present; initial commit made.

---

### Step 2 — Docker Compose Skeleton (Infra First)

**Goal**: Stand up infrastructure before any application code, to validate the platform incrementally.

**Tasks**:
1. Write `docker-compose.yml` with services: `cassandra`, `redis`, `rabbitmq`, `prometheus`, `grafana`. **Do not** add the API or Nginx yet.
2. Every service needs a `healthcheck`. Cassandra takes ~60s; use `start_period: 90s`.
3. Single bridge network `shortener-net`.
4. Mount `cassandra/init.cql` into the container and execute it via a one-shot `cassandra-init` service that runs after Cassandra is healthy.
5. Configure Redis with `--appendonly yes` (durability of the counter is critical).
6. Add a one-shot `redis-init` service that runs `SET url:counter 250000 NX` so we only set it on the first ever boot.
7. Expose ports: Cassandra 9042, Redis 6379, RabbitMQ 5672 + 15672, Prometheus 9090, Grafana 3000.

**Verification**:
```bash
docker compose up -d cassandra redis rabbitmq prometheus grafana
docker compose ps   # All should show "healthy"

docker compose exec cassandra cqlsh -e "DESCRIBE KEYSPACE shortener;"
# Should list the 3 tables

docker compose exec redis redis-cli GET url:counter
# Should return "250000"
```

**DoD**: All services start clean; healthchecks pass; Cassandra schema created; Redis counter equals 250000 on first boot and is *not* reset on subsequent boots.

---

### Step 3 — Spring Boot Skeleton

**Goal**: Boot a minimal Spring Boot app connected to Cassandra, Redis, RabbitMQ — no business logic yet.

**Tasks**:
1. Generate Spring Boot 3.3 project (Gradle Kotlin DSL) in `api/`. Dependencies: `spring-boot-starter-web`, `spring-boot-starter-data-cassandra`, `spring-boot-starter-data-redis`, `spring-boot-starter-amqp`, `spring-boot-starter-actuator`, `micrometer-registry-prometheus`, `spring-boot-starter-validation`.
2. Create `application.yml` with profiles `local` (host endpoints) and `docker` (service-name endpoints).
3. Configure Actuator: expose `health`, `info`, `prometheus`, `metrics`.
4. Add `GET /healthz` as a sanity endpoint returning `{"status":"ok"}`.
5. Add an `@EventListener(ApplicationReadyEvent.class)` that runs `SETNX url:counter 250000` at startup as a safety net (must never overwrite).

**Verification**:
```bash
cd api && ./gradlew bootRun --args='--spring.profiles.active=local'
curl http://localhost:8080/healthz
curl http://localhost:8080/actuator/prometheus | head -20
```

**DoD**: App boots; Actuator endpoints respond; Redis counter equals 250000; subsequent boots do not reset it.

---

### Step 4 — Base62 Encoder

**Goal**: Deterministic Base62 encoder with a secret-key-shuffled alphabet.

**Tasks**:
1. Create class `com.shortener.encoding.Base62Encoder` as a `@Bean` singleton.
2. Constructor takes `String secretKey`. Use `SHA-256(secretKey)` to derive a `long` seed for `java.util.Random`, then shuffle the standard alphabet `"0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"` using Fisher-Yates.
3. Implement `String encode(long id)` and `long decode(String code)` (decode is for tests, not production lookup).

**Reference logic** (must match this behavior):
```java
public String encode(long id) {
    if (id <= 0) throw new IllegalArgumentException("id must be positive");
    StringBuilder sb = new StringBuilder();
    while (id > 0) {
        sb.append(shuffledAlphabet.charAt((int)(id % 62)));
        id /= 62;
    }
    return sb.reverse().toString();
}
```

**Verification (unit tests)**:
- `encode(250000)` returns a 4-character string.
- `decode(encode(n)) == n` for n in `[1, 1_000_000_000]` (sample 1000 random ids).
- Two encoders with **different** secret keys produce different outputs for the same id.
- Two encoders with the **same** secret key produce identical outputs (determinism across JVM restarts).

**DoD**: All tests pass.

---

### Step 5 — URL Creation Endpoint

**Goal**: Implement `POST /api/v1/urls`.

**Tasks**:
1. `UrlController.create(@Valid @RequestBody CreateUrlRequest req)`.
2. Validation: `@URL` annotation + custom rule for non-recursive (host ≠ shortener host) + max 2048 chars.
3. Service flow:
    - `long id = redis.incr("url:counter")`
    - `String code = base62Encoder.encode(id)`
    - Insert into `urls_by_shortcode` (`short_code`, `long_url`, `url_id`, `created_at`)
    - Pre-warm cache: `SET url:cache:{code} {long_url} EX 604800`
    - Return DTO
4. `@ControllerAdvice` to map validation errors to a consistent `ErrorResponse` JSON body.

**Verification**:
```bash
curl -X POST http://localhost:8080/api/v1/urls \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com/very/long/path?with=params"}'
# Expect 201 + 4-char short_code

docker compose exec cassandra cqlsh -e "SELECT * FROM shortener.urls_by_shortcode;"
# Row should be present
```

**DoD**: Returns 201 with valid 4-char shortcode; row in Cassandra; Redis cache contains the entry; invalid URLs rejected with 400.

---

### Step 6 — Redirect Endpoint with Cache

**Goal**: Implement `GET /{shortcode}` with positive cache, negative cache, and async event publishing.

**Tasks**:
1. `RedirectController.redirect(@PathVariable String shortcode, HttpServletRequest req)`.
2. Lookup order:
    1. Negative cache (`url:negcache:{code}` exists) → 404 immediately.
    2. Positive cache (`GET url:cache:{code}` hit) → return 302 + publish event.
    3. Cassandra lookup → if found, populate positive cache (7d TTL) and return 302; if missing, set negative cache (60s TTL) and return 404.
3. **Publish access event to RabbitMQ asynchronously** (`@Async` on a dedicated bounded thread pool — not the request thread). A publish failure must log a warning but **must not** affect the HTTP response.
4. Extract client IP from `X-Forwarded-For` (first IP in the list); fall back to `RemoteAddr`.
5. Response headers: `Cache-Control: no-store, max-age=0`. Do not let intermediaries cache redirects.

**Cache-stampede mitigation**: wrap the Cassandra lookup + cache write in a `SET NX` lock on `url:lock:{code}` with 5s TTL. On lock contention, sleep 50 ms and retry the cache read. At most 3 retries before falling through to a direct DB read without locking.

**Verification**:
```bash
SHORT=$(curl -s -X POST http://localhost:8080/api/v1/urls \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com/"}' | jq -r .short_code)
curl -I http://localhost:8080/$SHORT
# Expect: HTTP/1.1 302; Location: https://example.com/

curl -I http://localhost:8080/doesnotexist
# Expect: 404 then 404 again (second hit served from negative cache)
```

**DoD**: Hot path serves redirects from Redis without hitting Cassandra (verify via metrics or by stopping Cassandra after pre-warming the cache); negative cache works; events arrive in RabbitMQ; **redirect latency is unaffected when RabbitMQ is stopped** (test this explicitly).

---

### Step 7 — Async Log Consumer

**Goal**: Persist access events from RabbitMQ to Cassandra without blocking redirects.

**Tasks**:
1. RabbitMQ producer in the redirect path uses `RabbitTemplate.convertAndSend("url.events", "url.accessed", payload)`.
2. Implement consumer `AccessLogConsumer` with `@RabbitListener(queues = "url.access.log")`.
3. Consumer writes to `requests_by_url`. Compute `time_bucket` as `yyyy-MM-dd-HH` in UTC from `request_time`.
4. Consumer also increments `access_counts` (counter table). Use prepared statements.
5. Configure retry: 3 attempts with exponential backoff; on final failure, route to DLQ.
6. Acknowledgment mode: manual ack after successful Cassandra write.
7. Declare exchange/queue/bindings via `@Configuration` (`RabbitMqTopology`) so they are created idempotently at startup.

**Verification**:
```bash
# Hit a shortcode N times, then query Cassandra:
for i in {1..50}; do curl -s -o /dev/null http://localhost:8080/$SHORT; done
sleep 3
docker compose exec cassandra cqlsh -e \
  "SELECT count(*) FROM shortener.requests_by_url WHERE short_code='$SHORT' AND time_bucket='$(date -u +%Y-%m-%d-%H)';"
# Expect: 50 (or very close; eventual consistency is acceptable up to ~5s)
```

**DoD**: 50 hits produce 50 rows in `requests_by_url`; `access_counts` increments accordingly; failed messages (force one with a malformed payload) land in DLQ.

---

### Step 8 — Stats Endpoint

**Goal**: Implement `GET /api/v1/urls/{shortcode}/stats`.

**Tasks**:
1. `StatsController` returning `total_count` (sum of `access_counts` rows in the requested date range) and `recent_requests` (last 100 from `requests_by_url`, limited across the relevant `time_bucket` partitions for the date range).
2. Validate the shortcode exists in `urls_by_shortcode`; otherwise 404.
3. Cap the date range at 90 days to prevent expensive queries.

**Verification**: `curl http://localhost:8080/api/v1/urls/$SHORT/stats` returns the expected count and recent events.

**DoD**: Endpoint returns accurate counts; unknown shortcode → 404; range > 90 days → 400.

---

### Step 9 — Nginx + Multiple Replicas + Rate Limiting

**Goal**: Add Nginx as load balancer in front of 3 Spring replicas, with per-IP rate limiting.

**Tasks**:
1. Define three API services in `docker-compose.yml` (`api-1`, `api-2`, `api-3`) using the same build, with `HOSTNAME` env distinct per replica.
2. Add `nginx` service exposing port 80, depending on the API services.
3. Write `nginx/nginx.conf`:
    - `upstream api_backend` with all three Spring instances, `least_conn` balancing, `max_fails=3 fail_timeout=10s` for health-based ejection.
    - Three `limit_req_zone` definitions (create/redirect/stats) keyed by `$binary_remote_addr`.
    - Three `location` blocks applying the matching `limit_req` with the rate limits from §5.
    - `proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;` and `X-Real-IP $remote_addr;`.
    - `proxy_pass http://api_backend;`.
    - `limit_req_status 429`.
4. Update Spring `BASE_URL` env to point to `http://localhost` so `short_url` reflects the Nginx port.

**Verification**:
```bash
docker compose up -d --build
# Run 100 quick creations from a single IP:
for i in {1..100}; do curl -s -o /dev/null -w "%{http_code}\n" -X POST \
  http://localhost/api/v1/urls -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com/'$i'"}'; done | sort | uniq -c
# Expect a mix of 201s and 429s.

# Verify load is spread:
docker compose logs api-1 api-2 api-3 | grep "POST /api/v1/urls" | awk '{print $1}' | sort | uniq -c
```

**DoD**: Three Spring replicas reachable through port 80; rate limits enforced (429 visible under load); requests distributed across replicas; `X-Forwarded-For` arrives correctly and is parsed by the redirect endpoint.

---

### Step 10 — Observability: Prometheus + Grafana

**Goal**: Metrics scraping and a Grafana dashboard for the key SLIs.

**Tasks**:
1. `observability/prometheus.yml`: scrape `api-1`, `api-2`, `api-3` on `/actuator/prometheus` every 10s. Add jobs for `prometheus` itself and for `rabbitmq` (enable the `rabbitmq_prometheus` plugin).
2. Add custom metrics in Spring using Micrometer:
    - `shortener_urls_created_total` (counter)
    - `shortener_redirects_total{result="hit|miss|notfound"}` (counter)
    - `shortener_cache_hits_total{type="positive|negative"}` (counter)
    - `shortener_cache_misses_total` (counter)
    - `shortener_redirect_latency_seconds` (timer/histogram) — wrap the controller method with `@Timed` or `Timer.Sample`
    - `shortener_log_consumer_lag_seconds` (gauge) — `now - request_time` measured at consumer
3. Provision Grafana via `observability/grafana/provisioning/`:
    - Datasource: Prometheus at `http://prometheus:9090`.
    - Dashboard JSON in `observability/grafana/dashboards/shortener.json` with these panels:
        - Requests per second (by endpoint)
        - Redirect latency p50/p95/p99
        - Cache hit ratio (hits / (hits + misses))
        - Errors per second (4xx, 5xx)
        - RabbitMQ queue depth (`rabbitmq_queue_messages_ready`)
        - JVM memory and GC pauses per replica
        - Log consumer lag

**Verification**:
- `http://localhost:9090/targets` shows all targets UP.
- `http://localhost:3000` (admin/admin) shows the provisioned dashboard with live data after running some traffic.

**DoD**: All Prometheus targets healthy; Grafana dashboard renders with non-empty panels under load.

---

### Step 11 — Unit and Integration Tests

**Goal**: Meaningful test coverage at unit + integration levels.

**Tasks**:
1. **Unit tests** (`api/src/test/java`):
    - `Base62EncoderTest`: roundtrip, determinism, length-at-250k, secret-sensitivity (covered in Step 4 — ensure they exist).
    - `UrlServiceTest`: mock Redis + Cassandra repo; verify INCR call, encode call, cache-write call.
    - `RedirectServiceTest`: mock dependencies; assert lookup order (negative → positive → DB); assert event publish happens on hit only.
    - Validation tests for `CreateUrlRequest` (invalid scheme, too long, self-referential host).
2. **Integration tests with Testcontainers**:
    - Spin up Cassandra, Redis, RabbitMQ containers per test class.
    - End-to-end: create URL → assert Cassandra row → GET shortcode → assert 302 + event in queue → consume event → assert log row in Cassandra.
    - Resilience: stop RabbitMQ container, verify redirect still returns 302 (event publish failure tolerated).
    - Stampede: parallel requests to the same uncached shortcode; assert Cassandra read count ≤ 2 (lock works).
3. Run tests in CI-mode: `./gradlew clean test`. Target ≥ 80% line coverage for service classes (use Jacoco).

**DoD**: All tests pass locally; coverage report generated at `api/build/reports/jacoco/test/html/index.html` and meets threshold.

---

### Step 12 — Load Tests with k6

**Goal**: Prove the system meets the NFR latency targets.

**Tasks**:
1. Create `load-tests/k6/setup.js`: seeds N URLs via the API and writes their shortcodes to a JSON file for the read scenario to consume.
2. Create `load-tests/k6/redirect.js`: read-heavy scenario.
    - Stages: ramp 0 → 200 VUs over 1 min; hold 200 VUs for 3 min; ramp down.
    - Each VU picks a random shortcode and GETs it.
    - Thresholds: `http_req_duration{status:302} p(99)<100`, `http_req_failed<0.01`.
3. Create `load-tests/k6/create.js`: write scenario.
    - Stages: ramp 0 → 20 VUs over 30 s; hold for 2 min.
    - Each VU POSTs a unique URL.
    - Thresholds: `http_req_duration{status:201} p(99)<300`.
4. Create `load-tests/k6/mixed.js`: combined scenario at 20:1 read:write ratio simulating real production load.
5. Provide a `load-tests/README.md` explaining how to run each scenario and how to interpret the Grafana dashboard while the test is running.

**Verification**:
```bash
docker compose up -d
docker run --rm -i --network host -v $PWD/load-tests/k6:/scripts grafana/k6 run /scripts/setup.js
docker run --rm -i --network host -v $PWD/load-tests/k6:/scripts grafana/k6 run /scripts/redirect.js
```

**DoD**: All thresholds pass on a modest dev machine (8 GB RAM). Save a screenshot of Grafana during the run to `docs/screenshots/`.

---

### Step 13 — README and Decision Log

**Goal**: Documentation worthy of a portfolio.

**Tasks**:
1. `README.md` sections (in this order):
    - **Project overview** (2–3 paragraphs)
    - **Architecture diagram** (the ASCII from §2 plus a higher-resolution PNG in `docs/`)
    - **Tech stack** with version table
    - **Quick start**: `cp .env.example .env && docker compose up -d`
    - **API reference**: curl examples for every endpoint
    - **Running tests**: unit, integration, load
    - **Observability**: how to open Grafana, what each panel means
    - **Performance results**: a table with measured p50/p95/p99 from the load tests + a Grafana screenshot
    - **Roadmap / what would change for real production** (Kubernetes, multi-region Cassandra, auth, custom domains)
2. `DECISIONS.md`: one short entry per major design choice. Required entries:
    - Why Redis `INCR` instead of UUID/snowflake
    - Why counter starts at 250000
    - Why Base62 with shuffled alphabet (vs. hash)
    - Why Cassandra (vs. Postgres) for this workload
    - Why RabbitMQ for logs (vs. synchronous writes or Kafka)
    - Why Nginx for rate limiting (vs. application-level)
    - Why no authentication (scope decision)
3. Add a `docs/screenshots/` folder with Grafana dashboard and k6 summary output.

**DoD**: A reader unfamiliar with the project can clone the repo and have it running + a Grafana dashboard live within 10 minutes following only the README.

---

## 9. Testing Strategy Summary

| Layer | Tool | What is tested |
|---|---|---|
| Unit | JUnit 5 + Mockito | Pure logic (encoder, validators, service orchestration with mocks) |
| Integration | Testcontainers | End-to-end flows against real Cassandra/Redis/RabbitMQ |
| Resilience | Testcontainers | Behavior when RabbitMQ down, when cache empty, stampede |
| Load | k6 | Throughput and latency SLIs from §5 |

---

## 10. Observability Summary

Metrics exposed via `/actuator/prometheus`:

| Metric | Type | Labels | Purpose |
|---|---|---|---|
| `shortener_urls_created_total` | Counter | — | Write volume |
| `shortener_redirects_total` | Counter | `result` | Read volume + outcome breakdown |
| `shortener_cache_hits_total` | Counter | `type` | Cache effectiveness |
| `shortener_cache_misses_total` | Counter | — | Cache effectiveness |
| `shortener_redirect_latency_seconds` | Histogram | — | Latency SLI |
| `shortener_log_consumer_lag_seconds` | Gauge | — | Async log freshness |

Grafana dashboard panels: see Step 10.

---

## 11. Deliverables Checklist

The agent must produce, at completion:

- [ ] Running `docker compose up -d` brings up the full stack
- [ ] All endpoints work as specified in §7
- [ ] Rate limiting verified (manual test produces 429s)
- [ ] Prometheus targets healthy; Grafana dashboard provisioned
- [ ] Unit + integration tests pass; ≥80% coverage on service code
- [ ] k6 load tests meet all thresholds; results captured in README
- [ ] `README.md` complete with quick start, API, observability, results
- [ ] `DECISIONS.md` covers every required entry
- [ ] No secrets committed; `.env` in `.gitignore`; `.env.example` complete

---

## Appendix A — Sample `nginx.conf` snippet

```nginx
limit_req_zone $binary_remote_addr zone=create_zone:10m rate=10r/s;
limit_req_zone $binary_remote_addr zone=redirect_zone:10m rate=100r/s;
limit_req_zone $binary_remote_addr zone=stats_zone:10m rate=5r/s;

upstream api_backend {
    least_conn;
    server api-1:8080 max_fails=3 fail_timeout=10s;
    server api-2:8080 max_fails=3 fail_timeout=10s;
    server api-3:8080 max_fails=3 fail_timeout=10s;
}

server {
    listen 80;

    location = / { return 404; }

    location /api/v1/urls {
        limit_req zone=create_zone burst=20 nodelay;
        limit_req_status 429;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_pass http://api_backend;
    }

    location ~ ^/api/v1/urls/[^/]+/stats$ {
        limit_req zone=stats_zone burst=10 nodelay;
        limit_req_status 429;
        proxy_pass http://api_backend;
    }

    location / {
        limit_req zone=redirect_zone burst=200 nodelay;
        limit_req_status 429;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_pass http://api_backend;
    }
}
```

## Appendix B — Sample `application.yml` (docker profile)

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

## Appendix C — Sample k6 redirect scenario

```javascript
import http from 'k6/http';
import { check } from 'k6';
import { SharedArray } from 'k6/data';

const codes = new SharedArray('codes', () =>
  JSON.parse(open('./shortcodes.json'))
);

export const options = {
  stages: [
    { duration: '1m', target: 200 },
    { duration: '3m', target: 200 },
    { duration: '30s', target: 0 },
  ],
  thresholds: {
    'http_req_duration{expected_response:true}': ['p(99)<100'],
    http_req_failed: ['rate<0.01'],
  },
};

export default function () {
  const code = codes[Math.floor(Math.random() * codes.length)];
  const res = http.get(`http://localhost/${code}`, { redirects: 0 });
  check(res, { 'is 302': (r) => r.status === 302 });
}
```

---

**End of PRD.** The agent should treat each step as a self-contained PR. Commit messages should reference the step number (e.g. `feat(step-5): URL creation endpoint`).