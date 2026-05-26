# Step 2 — Docker Compose Skeleton Design

**Date:** 2026-05-22
**Scope:** PRD Step 2 — stand up all infrastructure services before any application code.

---

## Goal

A `docker compose up -d` that brings up Cassandra, Redis, RabbitMQ, Prometheus, and Grafana with healthchecks passing, the Cassandra schema created, and the Redis counter initialized to 250000. No API or Nginx in this step.

---

## Services

### cassandra
- Image: `cassandra:4.1`
- Port: `9042:9042`
- Named volume: `cassandra-data:/var/lib/cassandra`
- Network: `shortener-net`
- Healthcheck: `cqlsh -e "describe cluster"` every 10s, timeout 5s, retries 10, `start_period: 90s`

### cassandra-init
- Image: `cassandra:4.1`
- Role: one-shot schema loader
- `depends_on: cassandra: condition: service_healthy`
- Command: `cqlsh cassandra -f /init.cql`
- Bind-mount: `./cassandra/init.cql:/init.cql:ro`
- `restart: "no"`
- Network: `shortener-net`

### redis
- Image: `redis:7.2`
- Port: `6379:6379`
- Named volume: `redis-data:/data`
- Command: `redis-server --appendonly yes`
- Network: `shortener-net`
- Healthcheck: `redis-cli ping` every 5s, timeout 3s, retries 5, `start_period: 10s`

### redis-init
- Image: `redis:7.2`
- Role: one-shot counter initializer
- `depends_on: redis: condition: service_healthy`
- Command: `redis-cli -h redis SET url:counter 250000 NX`
- `restart: "no"`
- Network: `shortener-net`

### rabbitmq
- Image: `rabbitmq:3.13-management`
- Ports: `5672:5672`, `15672:15672`
- Network: `shortener-net`
- Healthcheck: `rabbitmq-diagnostics ping` every 10s, timeout 5s, retries 10, `start_period: 30s`

### prometheus
- Image: `prom/prometheus` (latest stable — pinned to a digest in production)
- Port: `9090:9090`
- Bind-mount: `./observability/prometheus.yml:/etc/prometheus/prometheus.yml:ro`
- Network: `shortener-net`
- Healthcheck: `wget -qO- http://localhost:9090/-/healthy` every 10s, timeout 5s, retries 3, `start_period: 10s`

### grafana
- Image: `grafana/grafana` (latest stable)
- Port: `3000:3000`
- Bind-mounts:
  - `./observability/grafana/provisioning:/etc/grafana/provisioning:ro`
  - `./observability/grafana/dashboards:/var/lib/grafana/dashboards:ro`
- Env: `GF_SECURITY_ADMIN_PASSWORD=admin`
- Network: `shortener-net`
- Healthcheck: `wget -qO- http://localhost:3000/api/health` every 10s, timeout 5s, retries 3, `start_period: 15s`

---

## Network

Single bridge network: `shortener-net`

---

## Volumes

Named Docker volumes (portable, survive `docker compose down`, lost on `docker compose down -v`):
- `cassandra-data`
- `redis-data`

---

## Files Modified / Created

| File | Action |
|---|---|
| `docker-compose.yml` | Full replacement — all 7 services |
| `cassandra/init.cql` | Populated with PRD §6.1 schema (3 tables) |
| `observability/prometheus.yml` | Minimal valid config (global interval only; scrape targets added in Step 10) |

### cassandra/init.cql content

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

### observability/prometheus.yml minimal content

```yaml
global:
  scrape_interval: 10s
  evaluation_interval: 10s

scrape_configs: []
```

---

## Definition of Done (from PRD)

- `docker compose ps` shows all services healthy
- `cqlsh -e "DESCRIBE KEYSPACE shortener;"` lists 3 tables
- `redis-cli GET url:counter` returns `"250000"`
- Re-running `docker compose up` does NOT reset the counter (NX flag ensures idempotency)
