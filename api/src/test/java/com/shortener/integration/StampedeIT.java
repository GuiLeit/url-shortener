package com.shortener.integration;

import com.shortener.url.Url;
import com.shortener.url.UrlRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.verify;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class StampedeIT extends AbstractIT {

    static final String CODE = "stmpd1";
    static final int THREADS = 10;

    @SpyBean UrlRepository urlRepositorySpy;
    @Autowired TestRestTemplate restTemplate;
    @Autowired StringRedisTemplate redisTemplate;


    @BeforeEach
    void setupUrl() {
        urlRepositorySpy.save(new Url(CODE, "https://stampede-test.com", 100001L, Instant.now()));
        redisTemplate.delete("url:cache:" + CODE);
        redisTemplate.delete("url:negcache:" + CODE);
        redisTemplate.delete("url:lock:" + CODE);
    }

    @Test
    void concurrent_redirects_all_return_302_andCacheIsPopulated()
            throws InterruptedException, ExecutionException {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        List<Future<HttpStatusCode>> futures = new ArrayList<>();

        for (int i = 0; i < THREADS; i++) {
            futures.add(pool.submit(() ->
                restTemplate.exchange("/" + CODE, HttpMethod.GET, null, Void.class)
                    .getStatusCode()));
        }

        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        for (Future<HttpStatusCode> f : futures) {
            assertThat(f.get()).isEqualTo(HttpStatus.FOUND);
        }

        assertThat(redisTemplate.hasKey("url:cache:" + CODE)).isTrue();
        verify(urlRepositorySpy, atMost(3)).findById(eq(CODE));
    }
}
