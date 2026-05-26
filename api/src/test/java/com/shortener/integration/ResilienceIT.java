package com.shortener.integration;

import com.shortener.url.Url;
import com.shortener.url.UrlRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ResilienceIT extends AbstractIT {

    static final String CODE = "resil1";

    @MockBean RabbitTemplate rabbitTemplate;
    @Autowired UrlRepository urlRepository;
    @Autowired TestRestTemplate restTemplate;
    @Autowired StringRedisTemplate redisTemplate;

    @BeforeEach
    void setup() {
        urlRepository.save(new Url(CODE, "https://resilience-test.com", 200001L, Instant.now()));
        redisTemplate.delete("url:cache:" + CODE);
        redisTemplate.delete("url:negcache:" + CODE);
        doThrow(new AmqpException("simulated publish failure"))
            .when(rabbitTemplate).convertAndSend(anyString(), anyString(), (Object) any());
    }

    @Test
    void redirect_returns302_whenEventPublishFails() {
        ResponseEntity<Void> resp = restTemplate.exchange(
            "/" + CODE, HttpMethod.GET, null, Void.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(resp.getHeaders().getLocation().toString())
            .isEqualTo("https://resilience-test.com");
    }
}
