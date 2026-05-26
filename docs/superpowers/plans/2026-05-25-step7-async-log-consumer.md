# Step 7 — Async Log Consumer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement `AccessLogConsumer` that listens on `url.access.log`, persists access events to Cassandra `requests_by_url` and increments `access_counts`, with 3-retry exponential backoff and DLQ routing on final failure.

**Architecture:** Consumer uses `@RabbitListener` with manual ack (`ackMode = "MANUAL"`). A `RetryTemplate` wraps the Cassandra writes in-thread. On success: `channel.basicAck`. On all retries exhausted: `channel.basicNack(tag, false, false)` — the DLX already configured on `url.access.log` routes rejected messages to `url.access.log.dlq`. Counter increments use `CassandraOperations.execute(SimpleStatement)` because Cassandra counter tables forbid `INSERT` and must use `UPDATE`. Exchange/queue/DLX topology already declared in `RabbitMqTopology` (Step 6).

**Tech Stack:** Spring AMQP `@RabbitListener`, Spring Retry `RetryTemplate`, Spring Data Cassandra `CassandraOperations`, `com.datastax.oss.driver.api.core.uuid.Uuids.timeBased()` for version-1 UUIDs required by `timeuuid` column type.

---

### Task 1: Cassandra entity classes for requests_by_url

**Files:**
- Create: `api/src/main/java/com/shortener/consumer/AccessLogKey.java`
- Create: `api/src/main/java/com/shortener/consumer/AccessLogEntry.java`
- Create: `api/src/main/java/com/shortener/consumer/AccessLogRepository.java`
- Create: `api/src/test/java/com/shortener/consumer/AccessLogRepositoryTest.java`

**Schema reference (`cassandra/init.cql`):**
```cql
CREATE TABLE shortener.requests_by_url (
    short_code   text,
    time_bucket  text,        -- format: yyyy-MM-dd-HH (UTC)
    request_time timestamp,
    request_id   timeuuid,
    ip_address   text,
    user_agent   text,
    referer      text,
    PRIMARY KEY ((short_code, time_bucket), request_time, request_id)
) WITH CLUSTERING ORDER BY (request_time DESC);
```

The composite partition key `(short_code, time_bucket)` requires a `@PrimaryKeyClass` in Spring Data Cassandra.

- [ ] **Step 1: Write failing compile test**

```java
// api/src/test/java/com/shortener/consumer/AccessLogRepositoryTest.java
package com.shortener.consumer;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AccessLogRepositoryTest {
    @Test
    void repository_interface_compiles() {
        assertNotNull(AccessLogRepository.class);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew -p api test --tests "com.shortener.consumer.AccessLogRepositoryTest" 2>&1
```
Expected: FAIL — class not found

- [ ] **Step 3: Create AccessLogKey**

```java
// api/src/main/java/com/shortener/consumer/AccessLogKey.java
package com.shortener.consumer;

import org.springframework.data.cassandra.core.cql.Ordering;
import org.springframework.data.cassandra.core.cql.PrimaryKeyType;
import org.springframework.data.cassandra.core.mapping.PrimaryKeyClass;
import org.springframework.data.cassandra.core.mapping.PrimaryKeyColumn;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

@PrimaryKeyClass
public class AccessLogKey implements Serializable {

    @PrimaryKeyColumn(name = "short_code", ordinal = 0, type = PrimaryKeyType.PARTITIONED)
    private String shortCode;

    @PrimaryKeyColumn(name = "time_bucket", ordinal = 1, type = PrimaryKeyType.PARTITIONED)
    private String timeBucket;

    @PrimaryKeyColumn(name = "request_time", ordinal = 2, type = PrimaryKeyType.CLUSTERED, ordering = Ordering.DESCENDING)
    private Instant requestTime;

    @PrimaryKeyColumn(name = "request_id", ordinal = 3, type = PrimaryKeyType.CLUSTERED)
    private UUID requestId;

    public AccessLogKey() {}

    public AccessLogKey(String shortCode, String timeBucket, Instant requestTime, UUID requestId) {
        this.shortCode = shortCode;
        this.timeBucket = timeBucket;
        this.requestTime = requestTime;
        this.requestId = requestId;
    }

    public String getShortCode()    { return shortCode; }
    public String getTimeBucket()   { return timeBucket; }
    public Instant getRequestTime() { return requestTime; }
    public UUID getRequestId()      { return requestId; }

    public void setShortCode(String v)    { this.shortCode = v; }
    public void setTimeBucket(String v)   { this.timeBucket = v; }
    public void setRequestTime(Instant v) { this.requestTime = v; }
    public void setRequestId(UUID v)      { this.requestId = v; }
}
```

- [ ] **Step 4: Create AccessLogEntry**

```java
// api/src/main/java/com/shortener/consumer/AccessLogEntry.java
package com.shortener.consumer;

import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKey;
import org.springframework.data.cassandra.core.mapping.Table;

@Table("requests_by_url")
public class AccessLogEntry {

    @PrimaryKey
    private AccessLogKey key;

    @Column("ip_address")
    private String ipAddress;

    @Column("user_agent")
    private String userAgent;

    @Column("referer")
    private String referer;

    public AccessLogEntry() {}

    public AccessLogEntry(AccessLogKey key, String ipAddress, String userAgent, String referer) {
        this.key = key;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
        this.referer = referer;
    }

    public AccessLogKey getKey()     { return key; }
    public String getIpAddress()     { return ipAddress; }
    public String getUserAgent()     { return userAgent; }
    public String getReferer()       { return referer; }

    public void setKey(AccessLogKey k)       { this.key = k; }
    public void setIpAddress(String v)       { this.ipAddress = v; }
    public void setUserAgent(String v)       { this.userAgent = v; }
    public void setReferer(String v)         { this.referer = v; }
}
```

- [ ] **Step 5: Create AccessLogRepository**

```java
// api/src/main/java/com/shortener/consumer/AccessLogRepository.java
package com.shortener.consumer;

import org.springframework.data.cassandra.repository.CassandraRepository;

public interface AccessLogRepository extends CassandraRepository<AccessLogEntry, AccessLogKey> {}
```

- [ ] **Step 6: Run test to verify it passes**

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew -p api test --tests "com.shortener.consumer.AccessLogRepositoryTest" 2>&1
```
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add api/src/main/java/com/shortener/consumer/AccessLogKey.java \
        api/src/main/java/com/shortener/consumer/AccessLogEntry.java \
        api/src/main/java/com/shortener/consumer/AccessLogRepository.java \
        api/src/test/java/com/shortener/consumer/AccessLogRepositoryTest.java
git commit -m "feat(step-7): Cassandra entity classes for requests_by_url"
```

---

### Task 2: RetryTemplate bean

**Files:**
- Create: `api/src/main/java/com/shortener/config/RetryConfig.java`
- Create: `api/src/test/java/com/shortener/config/RetryConfigTest.java`
- Modify: `api/build.gradle.kts` — add `spring-retry` and `spring-aspects` dependencies

Spring Retry is not included transitively by spring-boot-starter-amqp. It must be added explicitly.

- [ ] **Step 1: Write failing test**

```java
// api/src/test/java/com/shortener/config/RetryConfigTest.java
package com.shortener.config;

import org.junit.jupiter.api.Test;
import org.springframework.retry.support.RetryTemplate;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class RetryConfigTest {
    @Test
    void accessLogRetryTemplate_bean_is_not_null() {
        RetryTemplate template = new RetryConfig().accessLogRetryTemplate();
        assertNotNull(template);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew -p api test --tests "com.shortener.config.RetryConfigTest" 2>&1
```
Expected: FAIL — class not found

- [ ] **Step 3: Add spring-retry dependency**

In `api/build.gradle.kts`, add to the `dependencies` block:
```kotlin
implementation("org.springframework.retry:spring-retry")
implementation("org.springframework:spring-aspects")
```

- [ ] **Step 4: Create RetryConfig**

```java
// api/src/main/java/com/shortener/config/RetryConfig.java
package com.shortener.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.support.RetryTemplate;

@Configuration
public class RetryConfig {

    @Bean("accessLogRetryTemplate")
    public RetryTemplate accessLogRetryTemplate() {
        return RetryTemplate.builder()
                .maxAttempts(3)
                .exponentialBackoff(1000, 2.0, 10000)
                .build();
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew -p api test --tests "com.shortener.config.RetryConfigTest" 2>&1
```
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add api/build.gradle.kts \
        api/src/main/java/com/shortener/config/RetryConfig.java \
        api/src/test/java/com/shortener/config/RetryConfigTest.java
git commit -m "feat(step-7): RetryTemplate bean with exponential backoff"
```

---

### Task 3: AccessLogConsumer implementation

**Files:**
- Create: `api/src/main/java/com/shortener/consumer/AccessLogConsumer.java`
- Create: `api/src/test/java/com/shortener/consumer/AccessLogConsumerTest.java`

**Behaviour spec:**
1. `@RabbitListener(queues = "url.access.log", ackMode = "MANUAL")` — Jackson2JsonMessageConverter (already on RabbitTemplate from Step 6) deserializes the JSON body to `AccessEvent`
2. Wrap all Cassandra work in `retryTemplate.execute(ctx -> { ... })`:
   a. `timeBucket = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH").withZone(ZoneOffset.UTC).format(event.getRequestTime())`
   b. `day = event.getRequestTime().atZone(ZoneOffset.UTC).toLocalDate()`
   c. Build `AccessLogKey(event.getShortCode(), timeBucket, event.getRequestTime(), Uuids.timeBased())`
   d. `accessLogRepository.save(new AccessLogEntry(key, ...))`
   e. `cassandraOperations.execute(SimpleStatement.newInstance("UPDATE shortener.access_counts SET count = count + 1 WHERE short_code = ? AND day = ?", event.getShortCode(), day))`
3. On success: `channel.basicAck(deliveryTag, false)`
4. On exception (retries exhausted): log error, `channel.basicNack(deliveryTag, false, false)` — DLX routes to `url.access.log.dlq`
5. Constructor-injected: `AccessLogRepository`, `CassandraOperations`, `@Qualifier("accessLogRetryTemplate") RetryTemplate`

**Import notes:**
- `@RabbitListener` → `org.springframework.amqp.rabbit.annotation.RabbitListener`
- `Channel` → `com.rabbitmq.client.Channel`
- `AmqpHeaders` → `org.springframework.amqp.support.AmqpHeaders`
- `@Header` → `org.springframework.messaging.handler.annotation.Header`
- `Uuids` → `com.datastax.oss.driver.api.core.uuid.Uuids`
- `SimpleStatement` → `com.datastax.oss.driver.api.core.cql.SimpleStatement`
- `Statement` → `com.datastax.oss.driver.api.core.cql.Statement`

- [ ] **Step 1: Write failing tests**

```java
// api/src/test/java/com/shortener/consumer/AccessLogConsumerTest.java
package com.shortener.consumer;

import com.datastax.oss.driver.api.core.cql.Statement;
import com.rabbitmq.client.Channel;
import com.shortener.event.AccessEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.cassandra.core.CassandraOperations;
import org.springframework.retry.support.RetryTemplate;

import java.io.IOException;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccessLogConsumerTest {

    @Mock AccessLogRepository accessLogRepository;
    @Mock CassandraOperations cassandraOperations;
    @Mock Channel channel;

    AccessLogConsumer consumer;

    @BeforeEach
    void setUp() {
        // Zero backoff so tests don't sleep
        RetryTemplate retryTemplate = RetryTemplate.builder().maxAttempts(3).fixedBackoff(0).build();
        consumer = new AccessLogConsumer(accessLogRepository, cassandraOperations, retryTemplate);
    }

    @Test
    void successful_consumption_saves_entry_and_acks() throws IOException {
        AccessEvent event = new AccessEvent("abc1", Instant.parse("2026-05-25T10:00:00Z"),
                "1.2.3.4", "Mozilla/5.0", "https://example.com");

        consumer.consume(event, channel, 1L);

        verify(accessLogRepository).save(any(AccessLogEntry.class));
        verify(cassandraOperations).execute(any(Statement.class));
        verify(channel).basicAck(1L, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    void time_bucket_is_formatted_as_yyyy_MM_dd_HH_utc() throws IOException {
        AccessEvent event = new AccessEvent("abc1", Instant.parse("2026-05-25T14:30:00Z"),
                "1.2.3.4", "Mozilla/5.0", null);

        consumer.consume(event, channel, 1L);

        ArgumentCaptor<AccessLogEntry> captor = ArgumentCaptor.forClass(AccessLogEntry.class);
        verify(accessLogRepository).save(captor.capture());
        assertEquals("2026-05-25-14", captor.getValue().getKey().getTimeBucket());
    }

    @Test
    void failure_after_all_retries_nacks_without_requeue() throws IOException {
        AccessEvent event = new AccessEvent("abc1", Instant.now(), "1.2.3.4", "ua", null);
        doThrow(new RuntimeException("Cassandra down")).when(accessLogRepository).save(any());

        consumer.consume(event, channel, 2L);

        verify(channel).basicNack(2L, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    void retries_three_times_before_giving_up() throws IOException {
        AccessEvent event = new AccessEvent("abc1", Instant.now(), "1.2.3.4", "ua", null);
        doThrow(new RuntimeException("transient")).when(accessLogRepository).save(any());

        consumer.consume(event, channel, 3L);

        verify(accessLogRepository, times(3)).save(any());
        verify(channel).basicNack(3L, false, false);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew -p api test --tests "com.shortener.consumer.AccessLogConsumerTest" 2>&1
```
Expected: FAIL — class not found

- [ ] **Step 3: Create AccessLogConsumer**

```java
// api/src/main/java/com/shortener/consumer/AccessLogConsumer.java
package com.shortener.consumer;

import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.datastax.oss.driver.api.core.uuid.Uuids;
import com.rabbitmq.client.Channel;
import com.shortener.event.AccessEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.cassandra.core.CassandraOperations;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class AccessLogConsumer {

    private static final Logger log = LoggerFactory.getLogger(AccessLogConsumer.class);
    private static final DateTimeFormatter BUCKET_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HH").withZone(ZoneOffset.UTC);

    private final AccessLogRepository accessLogRepository;
    private final CassandraOperations cassandraOperations;
    private final RetryTemplate retryTemplate;

    public AccessLogConsumer(AccessLogRepository accessLogRepository,
                             CassandraOperations cassandraOperations,
                             @Qualifier("accessLogRetryTemplate") RetryTemplate retryTemplate) {
        this.accessLogRepository = accessLogRepository;
        this.cassandraOperations = cassandraOperations;
        this.retryTemplate = retryTemplate;
    }

    @RabbitListener(queues = "url.access.log", ackMode = "MANUAL")
    public void consume(AccessEvent event, Channel channel,
                        @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        try {
            retryTemplate.execute(ctx -> {
                if (ctx.getRetryCount() > 0) {
                    log.warn("Retry {} for access log write on shortcode {}", ctx.getRetryCount(), event.getShortCode());
                }
                String timeBucket = BUCKET_FMT.format(event.getRequestTime());
                var day = event.getRequestTime().atZone(ZoneOffset.UTC).toLocalDate();
                AccessLogKey key = new AccessLogKey(
                        event.getShortCode(), timeBucket, event.getRequestTime(), Uuids.timeBased());
                accessLogRepository.save(
                        new AccessLogEntry(key, event.getIpAddress(), event.getUserAgent(), event.getReferer()));
                cassandraOperations.execute(SimpleStatement.newInstance(
                        "UPDATE shortener.access_counts SET count = count + 1 WHERE short_code = ? AND day = ?",
                        event.getShortCode(), day));
                return null;
            });
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("Failed to persist access event for {} after all retries: {}", event.getShortCode(), e.getMessage());
            try {
                channel.basicNack(deliveryTag, false, false);
            } catch (IOException ioEx) {
                log.error("Failed to nack message {}: {}", deliveryTag, ioEx.getMessage());
            }
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew -p api test --tests "com.shortener.consumer.AccessLogConsumerTest" 2>&1
```
Expected: 4/4 PASS

- [ ] **Step 5: Run all tests to check for regressions**

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew -p api test 2>&1
```
Expected: All tests pass

- [ ] **Step 6: Commit**

```bash
git add api/src/main/java/com/shortener/consumer/AccessLogConsumer.java \
        api/src/test/java/com/shortener/consumer/AccessLogConsumerTest.java
git commit -m "feat(step-7): AccessLogConsumer with retry and manual ack"
```

---

### Task 4: Update application.yml for consumer container settings

**Files:**
- Modify: `api/src/main/resources/application.yml`

The RabbitMQ listener container must be configured for manual ack and to not requeue rejected messages (so nacked messages go to DLX). This is global Spring Boot config, not code.

- [ ] **Step 1: Update application.yml**

In `api/src/main/resources/application.yml`, expand the `spring.rabbitmq` section to add listener config:

```yaml
spring:
  rabbitmq:
    host: rabbitmq
    port: 5672
    username: guest
    password: guest
    listener:
      simple:
        acknowledge-mode: manual
        default-requeue-rejected: false
```

The `local` profile override section at the bottom only overrides `rabbitmq.host`, so listener settings are inherited from the base profile — no changes needed there.

- [ ] **Step 2: Run all tests to confirm no regression**

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew -p api test 2>&1
```
Expected: All tests pass

- [ ] **Step 3: Commit**

```bash
git add api/src/main/resources/application.yml
git commit -m "feat(step-7): configure RabbitMQ listener for manual ack and DLQ routing"
```
