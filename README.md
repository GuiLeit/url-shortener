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
