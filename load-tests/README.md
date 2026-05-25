# Load Tests

k6 load tests for the URL shortener. Each script targets Nginx at `http://localhost` (port 80) and must be run **after** the full stack is up.

## Prerequisites

```bash
# Start the full stack from the repo root
docker compose up -d

# Wait until all services are healthy
docker compose ps   # all services should show "healthy"
```

## Running the Tests

All commands must be run from the **repo root** (`url-shortener/`).

### 1. Seed URLs (required before read/mixed scenarios)

Creates 200 short URLs and writes their codes to `load-tests/k6/shortcodes.json`.

```bash
docker run --rm -i --network host \
  -v "$PWD/load-tests/k6:/scripts" \
  grafana/k6 run /scripts/setup.js
```

Expected output:
```
Seeded 200/200 URLs
Wrote 200 shortcodes → /scripts/shortcodes.json
```

### 2. Redirect scenario (read-heavy)

Ramps to 200 VUs over 1 minute, holds for 3 minutes. Each VU picks a random shortcode and GETs it.

**Thresholds**: p99 < 100 ms, error rate < 1%.

```bash
docker run --rm -i --network host \
  -v "$PWD/load-tests/k6:/scripts" \
  grafana/k6 run /scripts/redirect.js
```

### 3. Create scenario (write)

Ramps to 20 VUs over 30 seconds, holds for 2 minutes. Each VU POSTs a unique URL.

**Thresholds**: p99 (201 responses) < 300 ms.

> **Note**: Nginx limits POST `/api/v1/urls` to 10 r/s per source IP. When running k6 locally (single IP), some requests will return 429. This is expected — the rate limiter is working correctly. The threshold allows up to 5% failures.

```bash
docker run --rm -i --network host \
  -v "$PWD/load-tests/k6:/scripts" \
  grafana/k6 run /scripts/create.js
```

### 4. Mixed scenario (20:1 read:write)

Runs 200 read VUs + 10 write VUs simultaneously (20:1 ratio), simulating realistic production traffic.

```bash
docker run --rm -i --network host \
  -v "$PWD/load-tests/k6:/scripts" \
  grafana/k6 run /scripts/mixed.js
```

## Interpreting Grafana During a Run

Open Grafana at **http://localhost:3000** (admin / admin).

Navigate to the **URL Shortener** dashboard. Key panels while a test is running:

| Panel | What to watch |
|---|---|
| **Redirect Latency (p99)** | Should stay below 100 ms during `redirect.js` |
| **Redirects Total** | Rate should match VU ramp in `redirect.js` |
| **URLs Created Total** | Rate shows write throughput in `create.js` |
| **Cache Hit Rate** | Rises as shortcodes warm up in Redis |
| **Consumer Lag** | Should stay near 0 — RabbitMQ consumer keeping up |
| **API Replicas** | Three instances (`api-1`, `api-2`, `api-3`) sharing load |

## Environment Variables

| Variable | Default | Purpose |
|---|---|---|
| `BASE_URL` | `http://localhost` | Override if Nginx is on a different host/port |
| `SEED_COUNT` | `200` | Number of URLs to seed in `setup.js` |

Example — run against a remote host:
```bash
docker run --rm -i --network host \
  -v "$PWD/load-tests/k6:/scripts" \
  -e BASE_URL=http://192.168.1.50 \
  grafana/k6 run /scripts/redirect.js
```

## Rate Limiting and Local Testing

Nginx is configured with per-IP rate limits:

- Redirects: 100 r/s (burst 200)
- Creates: 10 r/s (burst 20)

When running k6 locally, all VUs share the same source IP (127.0.0.1), so these limits apply to the entire load test aggregate. For maximum throughput testing, run k6 from a separate host or increase the Nginx limits in `nginx/nginx.conf`.
