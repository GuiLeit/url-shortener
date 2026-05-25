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
