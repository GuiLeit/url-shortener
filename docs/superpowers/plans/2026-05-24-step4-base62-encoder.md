# Step 4 — Base62 Encoder Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement a deterministic Base62 encoder with a secret-key-shuffled alphabet, verified by 4 unit tests written first (TDD).

**Architecture:** Pure Java class `Base62Encoder` with no Spring dependency — takes `String secretKey`, derives a deterministic shuffle via SHA-256 + Fisher-Yates, exposes `encode(long)` and `decode(String)`. A thin `EncoderConfig` `@Configuration` wires it as a Spring `@Bean` using `${shortener.secret-key}` from `application.yml`. Tests are plain JUnit 5 with no Spring context.

**Tech Stack:** Java 21, JUnit 5, `java.security.MessageDigest` (SHA-256), `java.util.Random` (seeded), Spring `@Configuration`/`@Bean`/`@Value`

---

## File Map

| File | Action | Purpose |
|---|---|---|
| `api/src/test/java/com/shortener/encoding/Base62EncoderTest.java` | Create | 4 unit tests — written first |
| `api/src/main/java/com/shortener/encoding/Base62Encoder.java` | Create | Encoder implementation |
| `api/src/main/java/com/shortener/config/EncoderConfig.java` | Create | Spring `@Bean` wiring |

---

### Task 1: Write failing tests

**Files:**
- Create: `api/src/test/java/com/shortener/encoding/Base62EncoderTest.java`

- [ ] **Step 1: Create test package directory and remove .gitkeep**

```bash
mkdir -p api/src/test/java/com/shortener/encoding
rm -f api/src/test/java/.gitkeep
```

- [ ] **Step 2: Write Base62EncoderTest.java**

Write this exact content to `api/src/test/java/com/shortener/encoding/Base62EncoderTest.java`:

```java
package com.shortener.encoding;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class Base62EncoderTest {

    @Test
    void encode_250000_returns4Chars() {
        Base62Encoder encoder = new Base62Encoder("any-secret");
        assertEquals(4, encoder.encode(250000).length());
    }

    @Test
    void decode_encode_roundtrip() {
        Base62Encoder encoder = new Base62Encoder("roundtrip-secret");
        Random rng = new Random(42);
        for (int i = 0; i < 1000; i++) {
            long id = (long)(rng.nextDouble() * 1_000_000_000L) + 1;
            assertEquals(id, encoder.decode(encoder.encode(id)),
                    "roundtrip failed for id=" + id);
        }
    }

    @Test
    void differentKeys_produceDifferentOutputs() {
        Base62Encoder enc1 = new Base62Encoder("secret-one");
        Base62Encoder enc2 = new Base62Encoder("secret-two");
        assertNotEquals(enc1.encode(12345), enc2.encode(12345));
    }

    @Test
    void sameKey_producesDeterministicOutput() {
        Base62Encoder enc1 = new Base62Encoder("same-secret");
        Base62Encoder enc2 = new Base62Encoder("same-secret");
        assertEquals(enc1.encode(12345), enc2.encode(12345));
    }
}
```

- [ ] **Step 3: Run tests — verify they FAIL (compilation error)**

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 api/gradlew -p api test --tests "com.shortener.encoding.Base62EncoderTest" 2>&1 | tail -15
```

Expected: build **fails** with a compilation error — `error: cannot find symbol` referencing `Base62Encoder`. This confirms the red phase of TDD.

---

### Task 2: Implement Base62Encoder

**Files:**
- Create: `api/src/main/java/com/shortener/encoding/Base62Encoder.java`

- [ ] **Step 1: Create encoding package directory**

```bash
mkdir -p api/src/main/java/com/shortener/encoding
```

- [ ] **Step 2: Write Base62Encoder.java**

Write this exact content to `api/src/main/java/com/shortener/encoding/Base62Encoder.java`:

```java
package com.shortener.encoding;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

public class Base62Encoder {

    private static final String ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    private final char[] shuffledAlphabet;
    private final Map<Character, Integer> reverseMap;

    public Base62Encoder(String secretKey) {
        char[] chars = ALPHABET.toCharArray();
        long seed = deriveSeed(secretKey);
        Random random = new Random(seed);
        for (int i = chars.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            char temp = chars[i];
            chars[i] = chars[j];
            chars[j] = temp;
        }
        this.shuffledAlphabet = chars;
        this.reverseMap = new HashMap<>(62);
        for (int i = 0; i < chars.length; i++) {
            reverseMap.put(chars[i], i);
        }
    }

    public String encode(long id) {
        if (id <= 0) throw new IllegalArgumentException("id must be positive");
        StringBuilder sb = new StringBuilder();
        while (id > 0) {
            sb.append(shuffledAlphabet[(int)(id % 62)]);
            id /= 62;
        }
        return sb.reverse().toString();
    }

    public long decode(String code) {
        long result = 0;
        for (char c : code.toCharArray()) {
            result = result * 62 + reverseMap.get(c);
        }
        return result;
    }

    private static long deriveSeed(String secretKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(secretKey.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(hash).getLong();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
```

- [ ] **Step 3: Run tests — verify all 4 PASS**

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 api/gradlew -p api test --tests "com.shortener.encoding.Base62EncoderTest" 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL` with `4 tests completed, 0 failed`.

---

### Task 3: Create EncoderConfig

**Files:**
- Create: `api/src/main/java/com/shortener/config/EncoderConfig.java`

- [ ] **Step 1: Create config package directory**

```bash
mkdir -p api/src/main/java/com/shortener/config
```

- [ ] **Step 2: Write EncoderConfig.java**

Write this exact content to `api/src/main/java/com/shortener/config/EncoderConfig.java`:

```java
package com.shortener.config;

import com.shortener.encoding.Base62Encoder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EncoderConfig {

    @Bean
    public Base62Encoder base62Encoder(@Value("${shortener.secret-key}") String secretKey) {
        return new Base62Encoder(secretKey);
    }
}
```

- [ ] **Step 3: Run full test suite to confirm nothing is broken**

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 api/gradlew -p api test 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`.

---

### Task 4: Commit

- [ ] **Step 1: Stage all files**

```bash
git add \
  api/src/test/java/com/shortener/encoding/Base62EncoderTest.java \
  api/src/main/java/com/shortener/encoding/Base62Encoder.java \
  api/src/main/java/com/shortener/config/EncoderConfig.java
```

Also stage the deleted .gitkeep:

```bash
git add api/src/test/java/.gitkeep
```

- [ ] **Step 2: Verify staged files**

```bash
git status
```

Expected: exactly the 3 new Java files + 1 deleted `.gitkeep` under "Changes to be committed". No build output or `.gradle/` dirs.

- [ ] **Step 3: Commit**

```bash
git commit -m "feat(step-4): Base62 encoder with unit tests"
```

- [ ] **Step 4: Verify**

```bash
git log --oneline | head -3
```

Expected: top commit is `feat(step-4): Base62 encoder with unit tests`.
