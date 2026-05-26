# Step 13 — README and Decision Log Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the placeholder `README.md` and `DECISIONS.md` with complete, portfolio-grade documentation, and create the `docs/screenshots/` directory.

**Architecture:** Three files only — `README.md` (portfolio README with all required sections), `DECISIONS.md` (design decision log with 7 required entries), and `docs/screenshots/.gitkeep` (directory placeholder). No code changes.

**Tech Stack:** Markdown, git

---

## File Map

| File | Action |
|---|---|
| `README.md` | Overwrite placeholder with full content |
| `DECISIONS.md` | Overwrite placeholder with full content |
| `docs/screenshots/.gitkeep` | Create (empty placeholder to track directory in git) |

---

### Task 1: README.md

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Write `README.md`**

Write this exact content to `/home/guilherme/Systems/Projects/url-shortener/README.md`:

```markdown
# URL Shortener

A portfolio-grade URL shortener built to demonstrate distributed-systems engineering patterns: load balancing, Redis-backed caching, Cassandra time-series storage, async messaging with RabbitMQ, observability with Prometheus and Grafana, and k6 load testing.

The service converts long URLs into 4-character Base62 shortcodes and redirects requests to the original URL. Every redirect is logged asynchronously — the log write never blocks the HTTP response. There is no user authentication; access control is handled at the Nginx layer with per-IP rate limiting.

The system is deployed as a Docker Compose stack with three stateless Spring Boot replicas behind Nginx, making it trivially runnable on a single VPS or laptop.

---

## Architecture

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

**Hot path (redirect):** Nginx → Spring → Redis cache hit → 302. On miss: Spring → Cassandra → populate Redis → 302. Log event published to RabbitMQ in fire-and-forget mode; never blocks the response.

**Cold path (creation):** Nginx → Spring → Redis `INCR` → Base62 encode → Cassandra insert → return shortcode.

---

## Tech Stack

| Technology | Version | Purpose |
|---|---|---|
| Java | 21 (Eclipse Temurin) | API runtime |
| Spring Boot | 3.3.4 | Web framework, DI, data clients |
| Cassandra | 4.1 | Persistent URL storage + time-series access logs |
| Redis | 7.2 | Atomic ID counter (`INCR`) + hot-URL cache |
| RabbitMQ | 3.13 | Async log event queue |
| Nginx | 1.27-alpine | Load balancer + L7 rate limiter |
| Prometheus | latest | Metrics scraping |
| Grafana | latest | Dashboards and SLI visualization |
| Docker Compose | v2 | Local orchestration |
| Gradle | 8.x (Kotlin DSL) | Build tool |
| Testcontainers | 1.19.x | Integration test infrastructure |
| k6 | latest | Load testing |

---

## Quick Start

```bash
git clone <repo-url>
cd url-shortener
cp .env.example .env          # edit SHORTENER_SECRET_KEY if desired
docker compose up -d --build
docker compose ps             # wait until all services show "healthy"
```

The API is available at **http://localhost** (via Nginx).  
Grafana is at **http://localhost:3000** (admin / admin).

---

## API Reference

All requests go through Nginx on port 80.

### Create a short URL

```bash
curl -s -X POST http://localhost/api/v1/urls \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com/very/long/path?q=1"}' | jq
```

Response `201 Created`:
```json
{
  "short_code": "Hk2p",
  "short_url": "http://localhost/Hk2p",
  "long_url": "https://example.com/very/long/path?q=1",
  "created_at": "2026-05-22T14:33:21Z"
}
```

Validation rules: URL must start with `http://` or `https://`, max 2048 characters, must not point back to the shortener itself. Returns `400` on failure.

Rate limit: 10 req/s per IP, burst 20.

### Redirect

```bash
curl -I http://localhost/Hk2p
```

Response `302 Found`:
```
Location: https://example.com/very/long/path?q=1
Cache-Control: no-store, max-age=0
```

Returns `404` if the shortcode is unknown (negative-cached for 60 s to prevent Cassandra scan storms).

Rate limit: 100 req/s per IP, burst 200.

### Stats

```bash
curl -s "http://localhost/api/v1/urls/Hk2p/stats?from=2026-05-01&to=2026-05-31" | jq
```

Response `200 OK`:
```json
{
  "short_code": "Hk2p",
  "total_count": 1543,
  "recent_requests": [
    {
      "request_time": "2026-05-22T14:33:21Z",
      "ip_address": "203.0.113.45",
      "user_agent": "Mozilla/5.0 ...",
      "referer": "https://example.com/page"
    }
  ]
}
```

`from` and `to` are optional ISO dates (default: last 7 days). Date range capped at 90 days. Returns `404` if shortcode unknown, `400` if range exceeds 90 days.

Rate limit: 5 req/s per IP, burst 10.

### Health

```bash
curl http://localhost/healthz
# {"status":"ok"}

curl http://localhost/actuator/health
# {"status":"UP"}
```

---

## Running Tests

### Unit + Integration Tests

```bash
cd api
./gradlew test
```

Tests include:
- Unit: `Base62EncoderTest`, `UrlServiceTest`, `RedirectServiceTest`, `CreateUrlRequestValidationTest`
- Integration (Testcontainers): end-to-end create→redirect→log flow, resilience against RabbitMQ failure, cache-stampede lock test
- Coverage report: `api/build/reports/jacoco/test/html/index.html` (≥ 80% on service classes)

### Load Tests

Ensure the full stack is running (`docker compose up -d`), then run from the repo root:

```bash
# 1. Seed 200 URLs (required before read/mixed scenarios)
docker run --rm -i --network host \
  -v "$PWD/load-tests/k6:/scripts" \
  grafana/k6 run /scripts/setup.js

# 2. Read-heavy scenario — 200 VUs, p99 < 100 ms
docker run --rm -i --network host \
  -v "$PWD/load-tests/k6:/scripts" \
  grafana/k6 run /scripts/redirect.js

# 3. Write scenario — 20 VUs, p99 < 300 ms
docker run --rm -i --network host \
  -v "$PWD/load-tests/k6:/scripts" \
  grafana/k6 run /scripts/create.js

# 4. Mixed 20:1 read:write scenario
docker run --rm -i --network host \
  -v "$PWD/load-tests/k6:/scripts" \
  grafana/k6 run /scripts/mixed.js
```

See `load-tests/README.md` for full details and Grafana interpretation guide.

---

## Observability

Open Grafana at **http://localhost:3000** (admin / admin). Navigate to the **URL Shortener** dashboard.

| Panel | Metric | What to watch |
|---|---|---|
| Requests per second | `http_server_requests_seconds_count` by endpoint | Traffic volume and endpoint breakdown |
| Redirect latency p50/p95/p99 | `shortener_redirect_latency_seconds` | SLI target: p99 < 100 ms on cache hit |
| Cache hit ratio | `shortener_cache_hits_total` / (hits + `shortener_cache_misses_total`) | Should rise toward 100% as Redis warms up |
| Errors per second | 4xx, 5xx by endpoint | Rate-limit 429s expected under load |
| RabbitMQ queue depth | `rabbitmq_queue_messages_ready` | Should trend toward 0 — consumer keeping up |
| JVM memory and GC pauses | per replica | Detect memory pressure across the 3 instances |
| Log consumer lag | `shortener_log_consumer_lag_seconds` | Should stay near 0 — log latency SLI |

Prometheus targets: **http://localhost:9090/targets** — all targets should show UP.

---

## Performance Results

Results measured on a dev machine (8 GB RAM) with the full Docker Compose stack running locally.

| Scenario | VUs | p50 | p95 | p99 | Error rate |
|---|---|---|---|---|---|
| Redirect (cache warm) | 200 | — | — | — | — |
| Create | 20 | — | — | — | — |
| Mixed 20:1 reads | 200 | — | — | — | — |
| Mixed 20:1 writes | 10 | — | — | — | — |

> Run the load tests (`load-tests/README.md`) and replace the `—` values with your measured results. Save a Grafana screenshot to `docs/screenshots/`.

---

## Roadmap — What Would Change for Production

| Concern | Current (portfolio) | Production approach |
|---|---|---|
| Orchestration | Docker Compose | Kubernetes with HPA for API replicas |
| Cassandra | Single node, `replication_factor=1` | Multi-region cluster, `NetworkTopologyStrategy` |
| Redis | Single instance | Redis Sentinel or Redis Cluster for HA |
| Authentication | None (public service) | API keys or OAuth 2.0 per tenant |
| Custom shortcodes | Not supported | Allow user-specified aliases with collision check |
| URL expiration | Never | TTL field + background cleanup job |
| TLS | Not configured | Terminate at Nginx with Let's Encrypt |
| Secret rotation | Restart required | Hot-reload `SHORTENER_SECRET_KEY` without invalidating existing codes (append-only alphabet registry) |
| Observability | Prometheus + Grafana | Add distributed tracing (OpenTelemetry → Tempo) |
```

- [ ] **Step 2: Verify key sections are present**

```bash
grep -c "##" /home/guilherme/Systems/Projects/url-shortener/README.md
```

Expected: `9` or more (each `##` heading).

```bash
grep "## Quick Start\|## API Reference\|## Running Tests\|## Observability\|## Performance" \
  /home/guilherme/Systems/Projects/url-shortener/README.md
```

Expected: all 5 lines printed.

- [ ] **Step 3: Commit**

```bash
git add README.md
git commit -m "feat(step-13): complete portfolio README"
```

---

### Task 2: DECISIONS.md

**Files:**
- Modify: `DECISIONS.md`

- [ ] **Step 1: Write `DECISIONS.md`**

Write this exact content to `/home/guilherme/Systems/Projects/url-shortener/DECISIONS.md`:

```markdown
# Decision Log

One entry per major design choice. Each entry states the decision, the alternatives considered, and the reason the current approach was chosen.

---

## 1. Redis INCR for ID generation (not UUID or snowflake)

**Decision:** Use `INCR url:counter` in Redis to generate monotonically increasing integer IDs, which are then Base62-encoded into shortcodes.

**Alternatives considered:**
- **UUID v4**: Random 128-bit IDs. No coordination needed, but produce long shortcodes (22+ chars in Base62) and are not sortable, making Cassandra range scans harder.
- **Twitter Snowflake / ULID**: Distributed, time-ordered 64-bit IDs. Good for multi-node ID generation, but adds operational complexity (clock skew handling, worker-ID assignment) for no benefit when Redis is already in the stack.

**Why INCR:** Redis `INCR` is atomic, sub-millisecond, and produces compact sequential integers. A 7-digit integer (e.g., 250000) encodes to a 4-character Base62 string. The tradeoff is that Redis becomes a single point of failure for ID generation — acceptable because Redis is already a hard dependency for the cache.

---

## 2. Counter initialized to 250000

**Decision:** Set `url:counter` to 250000 before first use.

**Why:** `encode(250000)` in Base62 produces a 4-character string (250000 = 1×62³ + ...). Starting below this value would produce 1-, 2-, or 3-character shortcodes, which are easily guessable (trivial enumeration attack). Starting at 250000 ensures all shortcodes are at least 4 characters from day one, providing ~14 million codes (62⁴ = 14,776,336) before growing to 5 characters.

---

## 3. Base62 with shuffled alphabet (not a hash function)

**Decision:** Use `encode(id)` with a secret-key-shuffled Base62 alphabet rather than a hash of the long URL.

**Alternatives considered:**
- **SHA-256 / MD5 prefix**: Non-colliding hashes are hard to guarantee with short prefixes. Two different URLs with the same 4-character hash prefix would require collision handling.
- **Random string**: Requires a uniqueness check against the database on every creation.
- **Sequential Base62 with standard alphabet** (`0-9A-Za-z`): Predictable — an attacker can enumerate all shortcodes in order.

**Why shuffled Base62:** Deterministic (same ID always produces the same code), collision-free by construction, and the shuffled alphabet makes sequential enumeration computationally impractical without knowing the secret key. Changing `SHORTENER_SECRET_KEY` rotates the entire alphabet, invalidating all existing codes — documented as a breaking change.

---

## 4. Cassandra for URL storage (not PostgreSQL)

**Decision:** Use Apache Cassandra for both the URL table and the access-log time-series.

**Alternatives considered:**
- **PostgreSQL**: Excellent for relational data, ACID transactions, and complex queries. Would work well for the URL table but is a poor fit for the access-log write pattern (millions of appends per day to a time-series).

**Why Cassandra:**
- The access-log table (`requests_by_url`) is pure append-only with a time-series partition key `(short_code, time_bucket)`. This is exactly the workload Cassandra is optimized for — linear write scalability, no lock contention.
- The URL table (`urls_by_shortcode`) is a simple key-value lookup by `short_code` (primary key). Cassandra handles this with single-partition reads.
- Horizontal scaling: adding nodes increases throughput linearly without schema changes.
- Counter tables (`access_counts`) are a first-class Cassandra feature, avoiding the read-modify-write race condition that would occur in PostgreSQL.

---

## 5. RabbitMQ for access logging (not synchronous writes or Kafka)

**Decision:** Publish access events to a RabbitMQ topic exchange on every redirect. A consumer in the same JAR persists them to Cassandra asynchronously.

**Alternatives considered:**
- **Synchronous Cassandra write on redirect**: Couples redirect latency to Cassandra write latency. A slow Cassandra node directly increases p99 redirect time. Violates the requirement that log writes must never block redirects.
- **Apache Kafka**: More durable, higher throughput, better replay semantics. But significantly heavier to operate (Zookeeper/KRaft, topic partition management, consumer group state). For a single-VPS portfolio project, RabbitMQ provides durable queuing with a management UI and Prometheus plugin at far lower operational cost.

**Why RabbitMQ:** Lightweight, durable queue with manual ack, dead-letter exchange support, and built-in retry semantics via Spring AMQP. The consumer acknowledges only after a successful Cassandra write; on failure it retries 3 times with exponential backoff before sending to the DLQ. This guarantees at-least-once delivery of access logs.

---

## 6. Nginx for rate limiting (not application-level)

**Decision:** Enforce per-IP rate limits at the Nginx layer, not inside the Spring application.

**Alternatives considered:**
- **Spring's `HandlerInterceptor` or Bucket4j**: Rate limiting inside the application. Works but runs after the JVM has already accepted the connection, parsed HTTP headers, and dispatched the request — wasting CPU on traffic that should be rejected at the edge.
- **API Gateway (Kong, AWS API GW)**: Correct for production multi-service architectures. Over-engineered for a single-service portfolio project.

**Why Nginx:** `limit_req_zone` runs at the kernel networking layer before any Spring code executes. A 429 from Nginx consumes ~1 KB and <1 ms; the same rejection from Spring consumes a thread, a heap allocation, and ~10 ms. Nginx also provides `least_conn` load balancing, passive health checks (`max_fails`/`fail_timeout`), and `X-Forwarded-For` injection — all in the same process, with zero application code.

---

## 7. No authentication

**Decision:** The service is a public, unauthenticated API. Any client can create shortcodes and any client can use them.

**Why:** This is an explicit scope decision. Adding authentication (API keys, OAuth 2.0, session management) is the correct production choice but doubles the implementation surface area without demonstrating additional distributed-systems concepts. The portfolio goal is to show load balancing, caching, async messaging, observability, and load testing — not auth flows. Rate limiting at Nginx provides a practical abuse mitigation layer. The `DECISIONS.md` entry documents this as a known gap, not an oversight.
```

- [ ] **Step 2: Verify all 7 entries are present**

```bash
grep -c "^## " /home/guilherme/Systems/Projects/url-shortener/DECISIONS.md
```

Expected: `7`

- [ ] **Step 3: Commit**

```bash
git add DECISIONS.md
git commit -m "feat(step-13): complete DECISIONS.md with all 7 required entries"
```

---

### Task 3: docs/screenshots/ directory

**Files:**
- Create: `docs/screenshots/.gitkeep`

- [ ] **Step 1: Create the directory placeholder**

```bash
touch /home/guilherme/Systems/Projects/url-shortener/docs/screenshots/.gitkeep
```

- [ ] **Step 2: Verify**

```bash
ls /home/guilherme/Systems/Projects/url-shortener/docs/screenshots/
```

Expected: `.gitkeep`

- [ ] **Step 3: Commit**

```bash
git add docs/screenshots/.gitkeep
git commit -m "feat(step-13): add docs/screenshots/ directory for load test and Grafana captures"
```

---

## Self-Review

**Spec coverage:**
- [x] README: project overview (2-3 paragraphs) — Task 1
- [x] README: architecture diagram (ASCII from §2) — Task 1
- [x] README: tech stack version table — Task 1
- [x] README: quick start (`cp .env.example .env && docker compose up -d`) — Task 1
- [x] README: API reference with curl examples for every endpoint — Task 1
- [x] README: running tests (unit, integration, load) — Task 1
- [x] README: observability (Grafana URL, panel descriptions) — Task 1
- [x] README: performance results table (placeholder with instruction) — Task 1
- [x] README: roadmap (Kubernetes, multi-region Cassandra, auth, custom domains) — Task 1
- [x] DECISIONS.md: Redis INCR vs UUID/snowflake — Task 2
- [x] DECISIONS.md: counter starts at 250000 — Task 2
- [x] DECISIONS.md: Base62 shuffled alphabet vs hash — Task 2
- [x] DECISIONS.md: Cassandra vs Postgres — Task 2
- [x] DECISIONS.md: RabbitMQ vs sync writes vs Kafka — Task 2
- [x] DECISIONS.md: Nginx rate limiting vs application-level — Task 2
- [x] DECISIONS.md: no authentication — Task 2
- [x] docs/screenshots/ folder — Task 3

**Placeholder scan:** Performance results table contains `—` values with explicit instruction to run load tests and replace them. This is intentional — the values cannot be predetermined and the instruction tells the implementer exactly what to do. No other placeholders.

**Field name consistency:** API response examples use `snake_case` throughout (`short_code`, `short_url`, `long_url`, `created_at`, `total_count`, `recent_requests`, `request_time`, `ip_address`, `user_agent`, `referer`) matching the Jackson `SNAKE_CASE` naming strategy configured in `application.yml`.
