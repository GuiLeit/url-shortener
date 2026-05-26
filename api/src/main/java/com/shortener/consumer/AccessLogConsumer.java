package com.shortener.consumer;

import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.datastax.oss.driver.api.core.uuid.Uuids;
import com.rabbitmq.client.Channel;
import com.shortener.event.AccessEvent;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.TimeGauge;
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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class AccessLogConsumer {

    private static final Logger log = LoggerFactory.getLogger(AccessLogConsumer.class);
    private static final DateTimeFormatter BUCKET_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HH").withZone(ZoneOffset.UTC);

    private final AccessLogRepository accessLogRepository;
    private final CassandraOperations cassandraOperations;
    private final RetryTemplate retryTemplate;
    private final AtomicLong lagNanos = new AtomicLong(0);

    public AccessLogConsumer(AccessLogRepository accessLogRepository,
                             CassandraOperations cassandraOperations,
                             @Qualifier("accessLogRetryTemplate") RetryTemplate retryTemplate,
                             MeterRegistry meterRegistry) {
        this.accessLogRepository = accessLogRepository;
        this.cassandraOperations = cassandraOperations;
        this.retryTemplate = retryTemplate;
        TimeGauge.builder("shortener.log.consumer.lag", lagNanos, TimeUnit.NANOSECONDS,
                        v -> (double) v.get())
                .description("Lag between event creation and Cassandra write")
                .register(meterRegistry);
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
                lagNanos.set(Duration.between(event.getRequestTime(), Instant.now()).toNanos());
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
