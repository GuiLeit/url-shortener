# Step 4 — Base62 Encoder Design

**Date:** 2026-05-24
**Scope:** PRD Step 4 — deterministic Base62 encoder with secret-key-shuffled alphabet, TDD.

---

## Goal

A `Base62Encoder` Spring bean that converts numeric IDs to short Base62 strings and back. The alphabet is shuffled deterministically from a secret key, making shortcodes unpredictable without the key. Verified entirely through unit tests before any endpoint integration.

---

## Files

| File | Action |
|---|---|
| `api/src/test/java/com/shortener/encoding/Base62EncoderTest.java` | Create first (TDD) |
| `api/src/main/java/com/shortener/encoding/Base62Encoder.java` | Create after tests |
| `api/src/main/java/com/shortener/config/EncoderConfig.java` | Create — Spring `@Bean` wiring |

---

## Base62Encoder

**Package:** `com.shortener.encoding`

**Constructor:** `Base62Encoder(String secretKey)`

1. Compute SHA-256 of `secretKey` (UTF-8 bytes)
2. Take first 8 bytes of the digest → `ByteBuffer.wrap(hash).getLong()` → `long seed`
3. Seed `java.util.Random(seed)`
4. Fisher-Yates shuffle of `"0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"` using that Random
5. Store shuffled alphabet as `char[]`
6. Build reverse lookup: `Map<Character, Integer>` mapping each char to its position in the shuffled alphabet

**`String encode(long id)`** — exactly the PRD reference logic:
```java
public String encode(long id) {
    if (id <= 0) throw new IllegalArgumentException("id must be positive");
    StringBuilder sb = new StringBuilder();
    while (id > 0) {
        sb.append(shuffledAlphabet[(int)(id % 62)]);
        id /= 62;
    }
    return sb.reverse().toString();
}
```

**`long decode(String code)`** — reverse lookup (for tests only, not used in production path):
```java
public long decode(String code) {
    long result = 0;
    for (char c : code.toCharArray()) {
        result = result * 62 + reverseMap.get(c);
    }
    return result;
}
```

---

## EncoderConfig

**Package:** `com.shortener.config`

```java
@Configuration
public class EncoderConfig {
    @Bean
    public Base62Encoder base62Encoder(@Value("${shortener.secret-key}") String secretKey) {
        return new Base62Encoder(secretKey);
    }
}
```

The `shortener.secret-key` property is already present in `application.yml` (defaults to `change-me-in-prod`).

---

## Tests (Base62EncoderTest)

**Package:** `com.shortener.encoding`
**Framework:** JUnit 5, no Spring context — plain unit tests, fast.

| Test | What it verifies |
|---|---|
| `encode_250000_returns4Chars` | `new Base62Encoder("any").encode(250000).length() == 4` |
| `decode_encode_roundtrip` | 1000 random ids in [1, 1_000_000_000]: `decode(encode(n)) == n` |
| `differentKeys_produceDifferentOutputs` | Two encoders with different keys → `encode(12345)` differs |
| `sameKey_producesDeterministicOutput` | Two encoders with same key → `encode(12345)` equals |

---

## Definition of Done (from PRD)

- `encode(250000)` returns a 4-character string
- `decode(encode(n)) == n` for 1000 sampled ids in [1, 1_000_000_000]
- Different secret keys produce different outputs for the same id
- Same secret key produces identical outputs across instantiations
- All 4 tests pass via `./gradlew test`
