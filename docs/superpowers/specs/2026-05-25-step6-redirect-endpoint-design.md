# Step 6 — Redirect Endpoint Design

**Date:** 2026-05-25
**Scope:** PRD Step 6 — implement `GET /{shortcode}` with positive cache, negative cache, cache-stampede lock, and async RabbitMQ event publishing.

---

## Goal

`GET /{shortcode}` looks up a short code with a tiered cache strategy, returns HTTP 302 with `Location` set to the original URL, and publishes an access event to RabbitMQ asynchronously (fire-and-forget). Unknown codes return 404. Invalid or missing codes never block the response path.

---

## New Packages

| Package | Files |
|---|---|
| `com.shortener.redirect` | `RedirectController`, `RedirectService` |
| `com.shortener.event` | `AccessEvent`, `AccessEventPublisher` |
| `com.shortener.config` | `AsyncConfig`, `RabbitMqTopology` |

`GlobalExceptionHandler` in `com.shortener.web` gains one new handler for `NotFoundException`.

---

## Data Flow

```
GET /{shortcode}
  → RedirectController.redirect(shortcode, HttpServletRequest)
    → extract IP (X-Forwarded-For first entry, fallback to RemoteAddr)
    → RedirectService.redirect(shortcode, ip, userAgent, referer)
      1. GET url:negcache:{code} → exists? throw NotFoundException (404)
      2. GET url:cache:{code}    → hit?   publish event, return longUrl
      3. Try SET url:lock:{code} NX EX 5
         - lock acquired: Cassandra lookup
             found:   SET url:cache:{code} (7d), DEL url:lock:{code}, publish event, return longUrl
             missing: SET url:negcache:{code} "0" (60s), DEL url:lock:{code}, throw NotFoundException
         - lock contention: sleep 50ms, retry step 2 (max 3 retries), then fall through to direct DB read
    → 302 Location: {longUrl}, Cache-Control: no-store, max-age=0

Validation failure / unknown code → GlobalExceptionHandler → 404 + ErrorResponse
```

---

## Files

### RedirectController

```java
@RestController
public class RedirectController {
    @GetMapping("/{shortcode}")
    public ResponseEntity<Void> redirect(@PathVariable String shortcode, HttpServletRequest request) { ... }
}
```

- Extracts `ip` from `X-Forwarded-For` (first token) or `request.getRemoteAddr()`
- Extracts `User-Agent` and `Referer` headers
- Calls `RedirectService.redirect(shortcode, ip, userAgent, referer)`
- Returns `ResponseEntity.status(302).location(URI.create(longUrl)).header("Cache-Control", "no-store, max-age=0").build()`

### RedirectService

Constructor-injected: `StringRedisTemplate`, `UrlRepository`, `AccessEventPublisher`, `@Value("${shortener.cache-ttl-seconds}") long cacheTtlSeconds`, `@Value("${shortener.negcache-ttl-seconds}") long negCacheTtlSeconds`.

`String redirect(String shortcode, String ip, String userAgent, String referer)`:

1. If `redisTemplate.hasKey("url:negcache:" + shortcode)` → throw `NotFoundException`
2. `String cached = redisTemplate.opsForValue().get("url:cache:" + shortcode)` → if non-null → publish event, return cached
3. Stampede loop (up to 3 retries):
   - Try `SET url:lock:{shortcode} "1" NX EX 5` (via `setIfAbsent` with `Duration.ofSeconds(5)`)
   - Lock acquired: break out of loop
   - Lock not acquired: sleep 50 ms, re-check positive cache (step 2 again); if still miss, continue loop
   - After loop exhausted without lock: fall through to direct DB read (no lock held)
4. Cassandra lookup via `urlRepository.findById(shortcode)`
   - Found: `SET url:cache:{shortcode} longUrl EX cacheTtlSeconds`, `DEL url:lock:{shortcode}`, publish event, return longUrl
   - Missing: `SET url:negcache:{shortcode} "0" EX negCacheTtlSeconds`, `DEL url:lock:{shortcode}`, throw `NotFoundException`

### NotFoundException

```java
package com.shortener.web;
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) { super(message); }
}
```

### GlobalExceptionHandler (addition)

New method:
```java
@ExceptionHandler(NotFoundException.class)
@ResponseStatus(HttpStatus.NOT_FOUND)
public ErrorResponse handleNotFound(NotFoundException ex) {
    return new ErrorResponse(ex.getMessage(), List.of());
}
```

### AccessEvent

```java
package com.shortener.event;
public class AccessEvent {
    private final String shortCode;
    private final Instant requestTime;
    private final String ipAddress;
    private final String userAgent;
    private final String referer;
    // all-args constructor + getters (snake_case via global Jackson config)
}
```

### AccessEventPublisher

```java
@Component
public class AccessEventPublisher {
    @Async("accessEventExecutor")
    public void publish(AccessEvent event) {
        try {
            rabbitTemplate.convertAndSend("url.events", "url.accessed", event);
        } catch (Exception e) {
            log.warn("Failed to publish access event for {}: {}", event.getShortCode(), e.getMessage());
        }
    }
}
```

### AsyncConfig

```java
@Configuration
@EnableAsync
public class AsyncConfig {
    @Bean("accessEventExecutor")
    public Executor accessEventExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(2);
        exec.setMaxPoolSize(8);
        exec.setQueueCapacity(500);
        exec.setThreadNamePrefix("access-event-");
        exec.initialize();
        return exec;
    }
}
```

### RabbitMqTopology

```java
@Configuration
public class RabbitMqTopology {
    // Exchanges
    @Bean TopicExchange urlEventsExchange()      { return new TopicExchange("url.events", true, false); }
    @Bean DirectExchange urlEventsDlx()          { return new DirectExchange("url.events.dlx", true, false); }
    // Queues
    @Bean Queue accessLogQueue()                 { return QueueBuilder.durable("url.access.log")
                                                       .withArgument("x-dead-letter-exchange", "url.events.dlx").build(); }
    @Bean Queue accessLogDlq()                   { return QueueBuilder.durable("url.access.log.dlq").build(); }
    // Bindings
    @Bean Binding accessLogBinding()             { return BindingBuilder.bind(accessLogQueue()).to(urlEventsExchange()).with("url.accessed"); }
    @Bean Binding dlqBinding()                   { return BindingBuilder.bind(accessLogDlq()).to(urlEventsDlx()).with("url.access.log"); }
}
```

---

## application.yml

No changes needed — `shortener.negcache-ttl-seconds: 60` and `shortener.cache-ttl-seconds: 604800` are already present.

---

## Tests

### RedirectServiceTest (Mockito, no Spring context)

Mocks: `StringRedisTemplate`, `ValueOperations<String,String>`, `UrlRepository`, `AccessEventPublisher`.

| Test | Verifies |
|---|---|
| `negcache_hit_returns_404_no_db` | `hasKey("url:negcache:X")` true → `NotFoundException` thrown, `urlRepository` never called |
| `poscache_hit_returns_url_no_db` | `get("url:cache:X")` non-null → longUrl returned, `urlRepository` never called, `publisher.publish()` called |
| `cache_miss_db_hit_populates_cache` | NX lock acquired, DB found → cache SET, event published, longUrl returned |
| `cache_miss_db_miss_sets_negcache` | NX lock acquired, DB empty → negcache SET, `NotFoundException` thrown |
| `lock_contention_retries_cache` | NX lock returns false → cache re-checked on retry, shortcut on second cache hit |

---

## Definition of Done (from PRD)

- `GET /{shortcode}` returns 302 `Location: {longUrl}` + `Cache-Control: no-store, max-age=0`
- `GET /doesnotexist` returns 404; second hit served from negative cache (Cassandra not called second time)
- Hot path served from Redis without hitting Cassandra (verify via stopping Cassandra after cache pre-warm)
- Access event arrives in RabbitMQ `url.access.log` queue
- Redirect latency unaffected when RabbitMQ is stopped
