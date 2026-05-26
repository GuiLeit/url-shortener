# Step 2 — Docker Compose Skeleton Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up all infrastructure services (Cassandra, Redis, RabbitMQ, Prometheus, Grafana) via Docker Compose with healthchecks, the Cassandra schema initialized, and the Redis counter seeded to 250000.

**Architecture:** Seven services on a single `shortener-net` bridge network. Two one-shot init containers (cassandra-init, redis-init) run after their respective services are healthy and then exit. Named volumes persist Cassandra and Redis data across restarts. No API or Nginx in this step.

**Tech Stack:** Docker Compose v2, Cassandra 4.1, Redis 7.2, RabbitMQ 3.13-management, Prometheus (latest stable), Grafana (latest stable)

---

## File Map

| File | Action | Purpose |
|---|---|---|
| `cassandra/init.cql` | Modify | Full Cassandra schema — 3 tables |
| `observability/prometheus.yml` | Modify | Minimal valid Prometheus config |
| `docker-compose.yml` | Modify | All 7 services, network, volumes |

---

### Task 1: Write Cassandra schema

**Files:**
- Modify: `cassandra/init.cql`

- [ ] **Step 1: Replace cassandra/init.cql with full schema**

```cql
CREATE KEYSPACE IF NOT EXISTS shortener
  WITH replication = { 'class': 'SimpleStrategy', 'replication_factor': 1 };

CREATE TABLE IF NOT EXISTS shortener.urls_by_shortcode (
    short_code text PRIMARY KEY,
    long_url   text,
    url_id     bigint,
    created_at timestamp
);

CREATE TABLE IF NOT EXISTS shortener.requests_by_url (
    short_code   text,
    time_bucket  text,
    request_time timestamp,
    request_id   timeuuid,
    ip_address   text,
    user_agent   text,
    referer      text,
    PRIMARY KEY ((short_code, time_bucket), request_time, request_id)
) WITH CLUSTERING ORDER BY (request_time DESC);

CREATE TABLE IF NOT EXISTS shortener.access_counts (
    short_code text,
    day        date,
    count      counter,
    PRIMARY KEY ((short_code), day)
);
```

- [ ] **Step 2: Verify file content**

```bash
cat cassandra/init.cql
```

Expected: file contains `CREATE KEYSPACE`, `urls_by_shortcode`, `requests_by_url`, `access_counts`.

---

### Task 2: Write minimal Prometheus config

**Files:**
- Modify: `observability/prometheus.yml`

- [ ] **Step 1: Replace observability/prometheus.yml with minimal valid config**

```yaml
global:
  scrape_interval: 10s
  evaluation_interval: 10s

scrape_configs: []
```

- [ ] **Step 2: Verify file content**

```bash
cat observability/prometheus.yml
```

Expected: contains `scrape_interval: 10s` and `scrape_configs: []`.

---

### Task 3: Write docker-compose.yml

**Files:**
- Modify: `docker-compose.yml`

- [ ] **Step 1: Replace docker-compose.yml with full service definitions**

```yaml
version: "3.9"

networks:
  shortener-net:
    driver: bridge

volumes:
  cassandra-data:
  redis-data:

services:

  cassandra:
    image: cassandra:4.1
    container_name: cassandra
    networks:
      - shortener-net
    ports:
      - "9042:9042"
    volumes:
      - cassandra-data:/var/lib/cassandra
    environment:
      CASSANDRA_CLUSTER_NAME: shortener-cluster
      CASSANDRA_DC: datacenter1
    healthcheck:
      test: ["CMD-SHELL", "cqlsh -e 'describe cluster' || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 10
      start_period: 90s

  cassandra-init:
    image: cassandra:4.1
    container_name: cassandra-init
    networks:
      - shortener-net
    depends_on:
      cassandra:
        condition: service_healthy
    volumes:
      - ./cassandra/init.cql:/init.cql:ro
    command: cqlsh cassandra -f /init.cql
    restart: "no"

  redis:
    image: redis:7.2
    container_name: redis
    networks:
      - shortener-net
    ports:
      - "6379:6379"
    volumes:
      - redis-data:/data
    command: redis-server --appendonly yes
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s
      timeout: 3s
      retries: 5
      start_period: 10s

  redis-init:
    image: redis:7.2
    container_name: redis-init
    networks:
      - shortener-net
    depends_on:
      redis:
        condition: service_healthy
    command: redis-cli -h redis SET url:counter 250000 NX
    restart: "no"

  rabbitmq:
    image: rabbitmq:3.13-management
    container_name: rabbitmq
    networks:
      - shortener-net
    ports:
      - "5672:5672"
      - "15672:15672"
    healthcheck:
      test: ["CMD", "rabbitmq-diagnostics", "ping"]
      interval: 10s
      timeout: 5s
      retries: 10
      start_period: 30s

  prometheus:
    image: prom/prometheus:latest
    container_name: prometheus
    networks:
      - shortener-net
    ports:
      - "9090:9090"
    volumes:
      - ./observability/prometheus.yml:/etc/prometheus/prometheus.yml:ro
    healthcheck:
      test: ["CMD-SHELL", "wget -qO- http://localhost:9090/-/healthy || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 3
      start_period: 10s

  grafana:
    image: grafana/grafana:latest
    container_name: grafana
    networks:
      - shortener-net
    ports:
      - "3000:3000"
    volumes:
      - ./observability/grafana/provisioning:/etc/grafana/provisioning:ro
      - ./observability/grafana/dashboards:/var/lib/grafana/dashboards:ro
    environment:
      GF_SECURITY_ADMIN_PASSWORD: admin
    healthcheck:
      test: ["CMD-SHELL", "wget -qO- http://localhost:3000/api/health || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 3
      start_period: 15s
```

- [ ] **Step 2: Verify file is valid YAML**

```bash
docker compose config --quiet && echo "YAML valid"
```

Expected: `YAML valid` (no errors).

---

### Task 4: Start services and verify

- [ ] **Step 1: Start infra services**

```bash
docker compose up -d cassandra redis rabbitmq prometheus grafana
```

Expected: Docker pulls images and starts containers. No error output.

- [ ] **Step 2: Wait for all services to become healthy**

Cassandra takes up to 90s. Poll until all 5 services are healthy (timeout 5 minutes):

```bash
for i in $(seq 1 60); do
  statuses=$(docker compose ps --format '{{.Health}}' cassandra redis rabbitmq prometheus grafana 2>/dev/null)
  unhealthy=$(echo "$statuses" | grep -v "^healthy$" | grep -v "^$" | wc -l)
  echo "Attempt $i/60 — unhealthy count: $unhealthy"
  [ "$unhealthy" -eq 0 ] && echo "All healthy!" && break
  sleep 5
done
```

Expected final output: `All healthy!`. If it times out after 60 attempts (5 min), check `docker compose logs cassandra` for startup errors.

- [ ] **Step 3: Run init containers**

```bash
docker compose up cassandra-init redis-init
```

Expected output includes:
- `cassandra-init` exits with code 0
- `redis-init` exits with code 0

- [ ] **Step 4: Verify Cassandra schema**

```bash
docker compose exec cassandra cqlsh -e "DESCRIBE KEYSPACE shortener;"
```

Expected: output lists `urls_by_shortcode`, `requests_by_url`, `access_counts` tables.

- [ ] **Step 5: Verify Redis counter**

```bash
docker compose exec redis redis-cli GET url:counter
```

Expected: `"250000"`

- [ ] **Step 6: Verify counter is NOT reset on subsequent up**

```bash
docker compose exec redis redis-cli SET url:counter 999999
docker compose up redis-init
docker compose exec redis redis-cli GET url:counter
```

Expected: still `"999999"` — NX flag prevents overwrite. Reset it back:

```bash
docker compose exec redis redis-cli SET url:counter 250000
```

- [ ] **Step 7: Verify RabbitMQ management UI is reachable**

```bash
curl -s -o /dev/null -w "%{http_code}" http://localhost:15672
```

Expected: `200`

- [ ] **Step 8: Verify Prometheus is healthy**

```bash
curl -s http://localhost:9090/-/healthy
```

Expected: `Prometheus Server is Healthy.`

- [ ] **Step 9: Verify Grafana is healthy**

```bash
curl -s http://localhost:3000/api/health | python3 -m json.tool
```

Expected: JSON with `"database": "ok"`.

---

### Task 5: Commit

- [ ] **Step 1: Stage changed files**

```bash
git add cassandra/init.cql observability/prometheus.yml docker-compose.yml
```

- [ ] **Step 2: Verify staged files**

```bash
git status
```

Expected: exactly those 3 files under "Changes to be committed". No other files.

- [ ] **Step 3: Commit**

```bash
git commit -m "feat(step-2): Docker Compose infrastructure skeleton"
```

Expected: commit created with that message.

- [ ] **Step 4: Verify**

```bash
git log --oneline | head -3
```

Expected: top commit is `feat(step-2): Docker Compose infrastructure skeleton`.
