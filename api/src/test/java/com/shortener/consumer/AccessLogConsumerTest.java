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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccessLogConsumerTest {

    @Mock AccessLogRepository accessLogRepository;
    @Mock CassandraOperations cassandraOperations;
    @Mock Channel channel;

    AccessLogConsumer consumer;

    @BeforeEach
    void setUp() {
        // Minimal backoff so tests don't sleep (0 is invalid, 1ms is effectively instant)
        RetryTemplate retryTemplate = RetryTemplate.builder().maxAttempts(3).fixedBackoff(1).build();
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
