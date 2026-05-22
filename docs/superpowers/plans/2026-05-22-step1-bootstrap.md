# Step 1 — Repository Bootstrap Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create the full directory skeleton, configuration stubs, and supporting files so every subsequent step has the correct project layout to build into.

**Architecture:** Pure scaffolding — no application logic. Creates empty stub files for all infra/config layers (Nginx, Cassandra, Compose, Grafana, k6) and a populated `.env.example`. All service hostnames in `.env.example` use Docker Compose service names, not `localhost`.

**Tech Stack:** Bash, Git

---

### Task 1: Update .gitignore

**Files:**
- Modify: `.gitignore`

- [ ] **Step 1: Replace .gitignore with full entry set**

```
build/
.gradle/
*.iml
.idea/
.env
out/
```

Write that exact content to `.gitignore` (replaces the current single-line file).

- [ ] **Step 2: Verify**

```bash
cat .gitignore
```

Expected output:
```
build/
.gradle/
*.iml
.idea/
.env
out/
```

---

### Task 2: Create directory structure

**Files:**
- Create: `api/src/main/java/.gitkeep`
- Create: `api/src/test/java/.gitkeep`
- Create: `observability/grafana/provisioning/.gitkeep`
- Create: `observability/grafana/dashboards/.gitkeep`
- Create: `load-tests/k6/.gitkeep`

- [ ] **Step 1: Create all directories with .gitkeep files**

```bash
mkdir -p api/src/main/java
mkdir -p api/src/test/java
mkdir -p nginx
mkdir -p cassandra
mkdir -p observability/grafana/provisioning
mkdir -p observability/grafana/dashboards
mkdir -p load-tests/k6
touch api/src/main/java/.gitkeep
touch api/src/test/java/.gitkeep
touch observability/grafana/provisioning/.gitkeep
touch observability/grafana/dashboards/.gitkeep
touch load-tests/k6/.gitkeep
```

- [ ] **Step 2: Verify tree**

```bash
find . -not -path './.git/*' -not -path './docs/*' -not -name 'PRD.md' | sort
```

Expected (order may vary):
```
.
./.gitignore
./api
./api/src
./api/src/main
./api/src/main/java
./api/src/main/java/.gitkeep
./api/src/test
./api/src/test/java
./api/src/test/java/.gitkeep
./cassandra
./load-tests
./load-tests/k6
./load-tests/k6/.gitkeep
./nginx
./observability
./observability/grafana
./observability/grafana/dashboards
./observability/grafana/dashboards/.gitkeep
./observability/grafana/provisioning
./observability/grafana/provisioning/.gitkeep
```

---

### Task 3: Create stub files

**Files:**
- Create: `api/build.gradle.kts`
- Create: `api/settings.gradle.kts`
- Create: `nginx/nginx.conf`
- Create: `cassandra/init.cql`
- Create: `observability/prometheus.yml`
- Create: `docker-compose.yml`
- Create: `README.md`
- Create: `DECISIONS.md`

- [ ] **Step 1: Create api/settings.gradle.kts**

```kotlin
rootProject.name = "url-shortener"
```

- [ ] **Step 2: Create api/build.gradle.kts**

```kotlin
// Dependencies and plugins will be added in Step 3 (Spring Boot skeleton)
plugins {
}
```

- [ ] **Step 3: Create nginx/nginx.conf**

```nginx
# Nginx configuration — populated in Step 9
```

- [ ] **Step 4: Create cassandra/init.cql**

```cql
-- Cassandra schema — populated in Step 2 (Docker Compose skeleton)
```

- [ ] **Step 5: Create observability/prometheus.yml**

```yaml
# Prometheus scrape config — populated in Step 10 (Observability)
```

- [ ] **Step 6: Create docker-compose.yml**

```yaml
# Docker Compose services — populated in Step 2 (Docker Compose skeleton)
version: "3.9"
```

- [ ] **Step 7: Create README.md**

```markdown
# URL Shortener

> Full content added in Step 13.
```

- [ ] **Step 8: Create DECISIONS.md**

```markdown
# Decision Log

> Full content added in Step 13.
```

---

### Task 4: Create .env.example

**Files:**
- Create: `.env.example`

- [ ] **Step 1: Write .env.example**

```dotenv
# Copy this file to .env and fill in the values before running docker compose up

# Base62 encoder secret — any string; changing this invalidates all existing shortcodes
SHORTENER_SECRET_KEY=change-me-in-prod

# Spring profile: "docker" for Docker Compose, "local" for running api outside Docker
SPRING_PROFILES_ACTIVE=docker

# Public base URL used to build short_url in API responses (no trailing slash)
BASE_URL=http://localhost

# Cassandra — uses Docker Compose service name when running inside Docker
CASSANDRA_CONTACT_POINTS=cassandra
CASSANDRA_PORT=9042
CASSANDRA_KEYSPACE=shortener
CASSANDRA_LOCAL_DATACENTER=datacenter1

# Redis — uses Docker Compose service name when running inside Docker
REDIS_HOST=redis
REDIS_PORT=6379

# RabbitMQ — uses Docker Compose service name when running inside Docker
RABBITMQ_HOST=rabbitmq
RABBITMQ_PORT=5672
RABBITMQ_USERNAME=guest
RABBITMQ_PASSWORD=guest
```

- [ ] **Step 2: Verify .env is gitignored**

```bash
echo "test" > .env
git status
```

Expected: `.env` does NOT appear in the "Untracked files" or "Changes" section — it should be silently ignored. Then delete it:

```bash
rm .env
```

---

### Task 5: Commit

- [ ] **Step 1: Stage all new files**

```bash
git add .gitignore \
  api/build.gradle.kts api/settings.gradle.kts \
  api/src/main/java/.gitkeep api/src/test/java/.gitkeep \
  nginx/nginx.conf \
  cassandra/init.cql \
  observability/prometheus.yml \
  observability/grafana/provisioning/.gitkeep \
  observability/grafana/dashboards/.gitkeep \
  load-tests/k6/.gitkeep \
  docker-compose.yml \
  .env.example \
  README.md \
  DECISIONS.md
```

- [ ] **Step 2: Verify staged files**

```bash
git status
```

Expected: all files listed above shown as "new file" or "modified" under "Changes to be committed". `.env` and `.idea/` must NOT appear.

- [ ] **Step 3: Commit**

```bash
git commit -m "feat(step-1): repository bootstrap"
```

Expected output contains: `feat(step-1): repository bootstrap`

- [ ] **Step 4: Verify final tree**

```bash
git log --oneline | head -3
```

Expected: top commit is `feat(step-1): repository bootstrap`.

```bash
find . -not -path './.git/*' -not -path './docs/*' | sort
```

Confirm all expected paths are present and `.env` is absent.
