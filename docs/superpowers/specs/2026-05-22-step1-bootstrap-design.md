# Step 1 — Repository Bootstrap Design

**Date:** 2026-05-22  
**Scope:** PRD Step 1 — create the directory skeleton, configuration files, and initial commit.

---

## Starting State

- Git repo already initialized with one "init" commit
- `.gitignore` contains only `.idea/`
- `PRD.md` present at root

---

## Deliverables

### Directory Structure

```
url-shortener/
├── api/
│   ├── build.gradle.kts
│   ├── settings.gradle.kts
│   └── src/
│       ├── main/java/
│       └── test/java/
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

All stub files (nginx.conf, init.cql, prometheus.yml, docker-compose.yml, build.gradle.kts, settings.gradle.kts) are empty or contain a minimal comment. `README.md` and `DECISIONS.md` are one-line stubs.

### .gitignore

Replaces the existing minimal file. Covers:
- `build/`
- `.gradle/`
- `*.iml`
- `.idea/`
- `.env`
- `out/`

### .env.example

Populated with all placeholders needed across the full project:
- `SHORTENER_SECRET_KEY`
- `SPRING_PROFILES_ACTIVE`
- `BASE_URL`
- `CASSANDRA_CONTACT_POINTS`
- `REDIS_HOST`
- `RABBITMQ_HOST`

---

## Commit

Single commit: `feat(step-1): repository bootstrap`

---

## Definition of Done (from PRD)

- Directory tree matches structure above
- `.gitignore` and `.env.example` present and correct
- Initial step commit made
