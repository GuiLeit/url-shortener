# Step 5 — URL Creation Endpoint Design

**Date:** 2026-05-25
**Scope:** PRD Step 5 — implement `POST /api/v1/urls`, returning a 4-char shortcode.

---

## Goal

`POST /api/v1/urls` accepts `{ "url": "..." }`, validates it, atomically increments a Redis counter, encodes the ID to a Base62 shortcode, persists to Cassandra, pre-warms the Redis cache, and returns a 201 response. Invalid URLs get a structured 400.

---

## New Packages

| Package | Files |
|---|---|
| `com.shortener.url` | `CreateUrlRequest`, `CreateUrlResponse`, `Url`, `UrlRepository`, `UrlService`, `UrlController` |
| `com.shortener.web` | `ErrorResponse`, `GlobalExceptionHandler` |
| `com.shortener.validation` | `NonRecursiveUrl`, `NonRecursiveUrlValidator` |

---

## Data Flow

```
POST /api/v1/urls
  → UrlController.create(@Valid CreateUrlRequest)
    → UrlService.create(longUrl)
      1. id = redisTemplate.increment("url:counter")
      2. code = encoder.encode(id)
      3. urlRepository.save(Url(code, longUrl, id, now))
      4. redisTemplate.set("url:cache:" + code, longUrl, 7 days)
      5. return CreateUrlResponse(code, baseUrl+"/"+code, longUrl, now)
  → HTTP 201

Validation failure → GlobalExceptionHandler → HTTP 400 + ErrorResponse
```

---

## Files

### CreateUrlRequest
- `String url` with `@NotBlank`, `@URL`, `@Size(max=2048)`, `@NonRecursiveUrl`

### CreateUrlResponse
Fields (serialized as snake_case via global Jackson config):
- `String shortCode`
- `String shortUrl`
- `String longUrl`
- `Instant createdAt`

### ErrorResponse
- `String message`
- `List<String> errors`

### Url (Cassandra entity)
```java
@Table("urls_by_shortcode")
public class Url {
    @PrimaryKey("short_code")  private String shortCode;
    @Column("long_url")        private String longUrl;
    @Column("url_id")          private Long urlId;
    @Column("created_at")      private Instant createdAt;
}
```

### UrlRepository
```java
public interface UrlRepository extends CassandraRepository<Url, String> {}
```

### UrlService
Constructor-injected: `StringRedisTemplate`, `UrlRepository`, `Base62Encoder`, `@Value("${shortener.base-url}") String baseUrl`.

Cache TTL: `Duration.ofSeconds(604800)` (7 days, matching `shortener.cache-ttl-seconds`).

### UrlController
```java
@RestController
@RequestMapping("/api/v1/urls")
public class UrlController {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreateUrlResponse create(@Valid @RequestBody CreateUrlRequest req) { ... }
}
```

### GlobalExceptionHandler
```java
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handle(MethodArgumentNotValidException ex) { ... }
}
```

Collects all `FieldError` messages into `ErrorResponse.errors`.

### NonRecursiveUrl (annotation)
Standard JSR-380 constraint annotation targeting `FIELD` and `PARAMETER`.

### NonRecursiveUrlValidator
Spring `@Component` implementing `ConstraintValidator<NonRecursiveUrl, String>`.
Injects `@Value("${shortener.base-url:http://localhost}") String baseUrl`.
Parses both URLs with `URI.create()`, compares hosts case-insensitively. Returns `true` if hosts differ (valid).

---

## application.yml addition

```yaml
spring:
  jackson:
    property-naming-strategy: SNAKE_CASE
```

Added to base section so all profiles inherit it.

---

## Tests

### UrlServiceTest (Mockito, no Spring context)
Mocks: `StringRedisTemplate`, `ValueOperations<String,String>`, `UrlRepository`, `Base62Encoder`.

Verifies:
- `increment("url:counter")` called once
- `encoder.encode(id)` called with the incremented value
- `urlRepository.save(...)` called with correct shortCode, longUrl, urlId
- Cache `set("url:cache:{code}", longUrl, Duration.ofSeconds(604800))` called
- Response has correct `shortCode`, `shortUrl`, `longUrl`, non-null `createdAt`

---

## Definition of Done (from PRD)

- `POST /api/v1/urls` returns 201 with 4-char `short_code`
- Row present in `shortener.urls_by_shortcode`
- `redis-cli GET url:cache:{code}` returns the long URL
- Invalid URLs (bad scheme, too long, recursive) rejected with 400 + JSON error body
