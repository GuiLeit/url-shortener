package com.shortener.integration;

import com.shortener.url.UrlRepository;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.cassandra.core.CassandraTemplate;
import org.springframework.data.cassandra.core.cql.CqlOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class UrlIT extends AbstractIT {

    @Autowired TestRestTemplate restTemplate;
    @Autowired UrlRepository urlRepository;
    @Autowired StringRedisTemplate redisTemplate;
    @Autowired CassandraTemplate cassandraTemplate;

    @Test
    void createUrl_returns201_persistsToDb_andPopulatesCache() {
        ResponseEntity<Map> resp = restTemplate.exchange(
            "/api/v1/urls", HttpMethod.POST,
            jsonBody("{\"url\":\"https://integration-test.com/path\"}"),
            Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String code = (String) resp.getBody().get("short_code");
        assertThat(code).hasSize(4);
        assertThat(urlRepository.existsById(code)).isTrue();
        assertThat(redisTemplate.hasKey("url:cache:" + code)).isTrue();
    }

    @Test
    void redirect_returns302_withCorrectLocation() {
        ResponseEntity<Map> createResp = restTemplate.exchange(
            "/api/v1/urls", HttpMethod.POST,
            jsonBody("{\"url\":\"https://redirect-integration-test.com\"}"),
            Map.class);
        String code = (String) createResp.getBody().get("short_code");

        ResponseEntity<Void> resp = restTemplate.exchange(
            "/" + code, HttpMethod.GET, null, Void.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(resp.getHeaders().getLocation().toString())
            .isEqualTo("https://redirect-integration-test.com");
    }

    @Test
    void redirect_unknownShortcode_returns404() {
        ResponseEntity<Void> resp = restTemplate.exchange(
            "/zzzzz", HttpMethod.GET, null, Void.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void consumer_persistsAccessCount_afterRedirect() {
        ResponseEntity<Map> createResp = restTemplate.exchange(
            "/api/v1/urls", HttpMethod.POST,
            jsonBody("{\"url\":\"https://consumer-integration-test.com\"}"),
            Map.class);
        String code = (String) createResp.getBody().get("short_code");

        restTemplate.exchange("/" + code, HttpMethod.GET, null, Void.class);

        CqlOperations cql = cassandraTemplate.getCqlOperations();
        Awaitility.await()
            .atMost(15, TimeUnit.SECONDS)
            .pollInterval(500, TimeUnit.MILLISECONDS)
            .untilAsserted(() -> {
                List<Long> counts = cql.query(
                    "SELECT count FROM shortener.access_counts WHERE short_code = ?",
                    (row, n) -> row.getLong("count"),
                    code);
                long total = counts.stream().mapToLong(Long::longValue).sum();
                assertThat(total).isGreaterThanOrEqualTo(1L);
            });
    }

    private HttpEntity<String> jsonBody(String json) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(json, h);
    }
}
