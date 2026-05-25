package com.shortener.integration;

import com.datastax.oss.driver.api.core.CqlSession;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.CassandraContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;

abstract class AbstractIT {

    @Container
    @ServiceConnection
    static final CassandraContainer<?> CASSANDRA =
            new CassandraContainer<>("cassandra:4.1");

    @Container
    @ServiceConnection
    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7.2-alpine").withExposedPorts(6379);

    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBITMQ =
            new RabbitMQContainer("rabbitmq:3.13-management");

    @BeforeAll
    static void initSchema() {
        try (CqlSession session = CqlSession.builder()
                .addContactPoint(CASSANDRA.getContactPoint())
                .withLocalDatacenter("datacenter1")
                .build()) {
            session.execute(
                "CREATE KEYSPACE IF NOT EXISTS shortener " +
                "WITH replication = {'class':'SimpleStrategy','replication_factor':1}");
            session.execute(
                "CREATE TABLE IF NOT EXISTS shortener.urls_by_shortcode (" +
                "short_code text PRIMARY KEY, long_url text, url_id bigint, " +
                "created_at timestamp)");
            session.execute(
                "CREATE TABLE IF NOT EXISTS shortener.requests_by_url (" +
                "short_code text, time_bucket text, request_time timestamp, " +
                "request_id timeuuid, ip_address text, user_agent text, referer text, " +
                "PRIMARY KEY ((short_code, time_bucket), request_time, request_id)) " +
                "WITH CLUSTERING ORDER BY (request_time DESC)");
            session.execute(
                "CREATE TABLE IF NOT EXISTS shortener.access_counts (" +
                "short_code text, day date, count counter, " +
                "PRIMARY KEY ((short_code), day))");
        }
    }
}
