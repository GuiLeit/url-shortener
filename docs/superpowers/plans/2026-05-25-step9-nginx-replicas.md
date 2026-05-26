# Step 9 — Nginx + Multiple Replicas + Rate Limiting Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Dockerfile for the Spring Boot API, wire three replica services (`api-1`, `api-2`, `api-3`) into Docker Compose, add an Nginx service with per-IP rate limiting and least-conn load balancing.

**Architecture:** Three identical Spring Boot containers share the same image build. Nginx listens on port 80, load-balances across them with `least_conn`, and enforces three separate `limit_req_zone` policies (create/redirect/stats). The API containers are not port-exposed externally — all traffic flows through Nginx. `BASE_URL=http://localhost` ensures `short_url` in API responses reflects the Nginx-exposed URL.

**Tech Stack:** Docker multi-stage build (eclipse-temurin:21-jdk → eclipse-temurin:21-jre-jammy), Docker Compose v2, Nginx 1.27-alpine.

**No Java code changes.** All changes are infra/config files.

---

### Task 1: Dockerfile for the API

**Files:**
- Create: `api/Dockerfile`

Multi-stage build: Gradle build in JDK stage, then copy the fat jar to a minimal JRE image. Tests are skipped during Docker build (`-x test`) because there's no real infrastructure inside the build container.

- [ ] **Step 1: Create `api/Dockerfile`**

```dockerfile
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /app
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN ./gradlew dependencies --no-daemon -q 2>/dev/null || true
COPY src ./src
RUN ./gradlew bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

`curl` is installed in the runtime stage so Docker healthchecks can use `curl -f http://localhost:8080/actuator/health`.

- [ ] **Step 2: Verify the Dockerfile builds (optional local check)**

If Docker is available locally:
```bash
docker build -t url-shortener-api:dev ./api
```
Expected: BUILD succeeded, image created. If Docker is not available, skip — it will be validated in Task 3.

- [ ] **Step 3: Commit**

```bash
git -C /home/guilherme/Systems/Projects/url-shortener add api/Dockerfile
git -C /home/guilherme/Systems/Projects/url-shortener commit -m "feat(step-9): Dockerfile for Spring Boot API"
```

---

### Task 2: nginx.conf

**Files:**
- Modify: `nginx/nginx.conf` (currently a placeholder comment)

Rate limits from PRD §5:
- `POST /api/v1/urls`: 10 req/s per IP, burst 20
- `GET /{shortcode}`: 100 req/s per IP, burst 200
- `GET /api/v1/urls/*/stats`: 5 req/s per IP, burst 10

Location matching order (Nginx evaluates regex `~` before plain prefix matches):
1. `location = /` — exact root → 404 (nothing at root)
2. `location ~ ^/api/v1/urls/[^/]+/stats$` — stats regex (wins over prefix below for stats URLs)
3. `location /api/v1/urls` — create + other api prefix
4. `location /` — catch-all for shortcode redirects

- [ ] **Step 1: Write nginx.conf**

```nginx
# nginx/nginx.conf
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

    location = / {
        return 404;
    }

    location ~ ^/api/v1/urls/[^/]+/stats$ {
        limit_req zone=stats_zone burst=10 nodelay;
        limit_req_status 429;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_pass http://api_backend;
    }

    location /api/v1/urls {
        limit_req zone=create_zone burst=20 nodelay;
        limit_req_status 429;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_pass http://api_backend;
    }

    location / {
        limit_req zone=redirect_zone burst=200 nodelay;
        limit_req_status 429;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_pass http://api_backend;
    }
}
```

- [ ] **Step 2: Commit**

```bash
git -C /home/guilherme/Systems/Projects/url-shortener add nginx/nginx.conf
git -C /home/guilherme/Systems/Projects/url-shortener commit -m "feat(step-9): Nginx config with rate limiting and load balancing"
```

---

### Task 3: Update docker-compose.yml

**Files:**
- Modify: `docker-compose.yml`

Add three API replica services and an Nginx service. Key points:
- `api-1`, `api-2`, `api-3` all build from `./api` (same Dockerfile)
- Each has `hostname` set so `HOSTNAME` env used in metrics tags is deterministic
- They depend on infra services being healthy AND one-shot init services completing
- Nginx depends on all three API services
- API replicas are NOT port-exposed externally (only Nginx port 80 is public)
- `BASE_URL=${BASE_URL:-http://localhost}` so `short_url` uses the Nginx URL

Read the current `docker-compose.yml` first to understand the existing structure, then append the new services.

- [ ] **Step 1: Read current docker-compose.yml**

Read `docker-compose.yml` to understand what's there.

- [ ] **Step 2: Add API replica services and Nginx service**

Append these services to the existing `docker-compose.yml` (after the `grafana` service, before the end of the file):

```yaml
  api-1:
    build:
      context: ./api
      dockerfile: Dockerfile
    container_name: api-1
    hostname: api-1
    networks:
      - shortener-net
    environment:
      SPRING_PROFILES_ACTIVE: docker
      SHORTENER_SECRET_KEY: ${SHORTENER_SECRET_KEY:-change-me-in-prod}
      BASE_URL: ${BASE_URL:-http://localhost}
      HOSTNAME: api-1
    depends_on:
      cassandra:
        condition: service_healthy
      redis:
        condition: service_healthy
      rabbitmq:
        condition: service_healthy
      cassandra-init:
        condition: service_completed_successfully
      redis-init:
        condition: service_completed_successfully
    healthcheck:
      test: ["CMD-SHELL", "curl -fsS http://localhost:8080/actuator/health | grep -q UP || exit 1"]
      interval: 15s
      timeout: 5s
      retries: 10
      start_period: 90s

  api-2:
    build:
      context: ./api
      dockerfile: Dockerfile
    container_name: api-2
    hostname: api-2
    networks:
      - shortener-net
    environment:
      SPRING_PROFILES_ACTIVE: docker
      SHORTENER_SECRET_KEY: ${SHORTENER_SECRET_KEY:-change-me-in-prod}
      BASE_URL: ${BASE_URL:-http://localhost}
      HOSTNAME: api-2
    depends_on:
      cassandra:
        condition: service_healthy
      redis:
        condition: service_healthy
      rabbitmq:
        condition: service_healthy
      cassandra-init:
        condition: service_completed_successfully
      redis-init:
        condition: service_completed_successfully
    healthcheck:
      test: ["CMD-SHELL", "curl -fsS http://localhost:8080/actuator/health | grep -q UP || exit 1"]
      interval: 15s
      timeout: 5s
      retries: 10
      start_period: 90s

  api-3:
    build:
      context: ./api
      dockerfile: Dockerfile
    container_name: api-3
    hostname: api-3
    networks:
      - shortener-net
    environment:
      SPRING_PROFILES_ACTIVE: docker
      SHORTENER_SECRET_KEY: ${SHORTENER_SECRET_KEY:-change-me-in-prod}
      BASE_URL: ${BASE_URL:-http://localhost}
      HOSTNAME: api-3
    depends_on:
      cassandra:
        condition: service_healthy
      redis:
        condition: service_healthy
      rabbitmq:
        condition: service_healthy
      cassandra-init:
        condition: service_completed_successfully
      redis-init:
        condition: service_completed_successfully
    healthcheck:
      test: ["CMD-SHELL", "curl -fsS http://localhost:8080/actuator/health | grep -q UP || exit 1"]
      interval: 15s
      timeout: 5s
      retries: 10
      start_period: 90s

  nginx:
    image: nginx:1.27-alpine
    container_name: nginx
    networks:
      - shortener-net
    ports:
      - "80:80"
    volumes:
      - ./nginx/nginx.conf:/etc/nginx/conf.d/default.conf:ro
    depends_on:
      api-1:
        condition: service_healthy
      api-2:
        condition: service_healthy
      api-3:
        condition: service_healthy
    healthcheck:
      test: ["CMD-SHELL", "wget -qO- http://localhost/healthz | grep -q ok || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 10s
```

**Important Nginx config note:** The config file is mounted to `/etc/nginx/conf.d/default.conf` (NOT `/etc/nginx/nginx.conf`). The default Nginx Alpine image includes `/etc/nginx/nginx.conf` which already has `include /etc/nginx/conf.d/*.conf`, so mounting to `conf.d/default.conf` is the correct approach and keeps the `events {}` and `http {}` blocks intact.

- [ ] **Step 3: Verify the YAML is valid**

```bash
cd /home/guilherme/Systems/Projects/url-shortener && docker compose config --quiet 2>&1 | head -20
```
Expected: no errors (empty output or warnings only)

- [ ] **Step 4: Commit**

```bash
git -C /home/guilherme/Systems/Projects/url-shortener add docker-compose.yml
git -C /home/guilherme/Systems/Projects/url-shortener commit -m "feat(step-9): add api replicas and nginx to docker-compose"
```
