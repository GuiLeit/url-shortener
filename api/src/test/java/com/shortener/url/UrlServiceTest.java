package com.shortener.url;

import com.shortener.encoding.Base62Encoder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UrlServiceTest {

    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOps;
    @Mock UrlRepository urlRepository;
    @Mock Base62Encoder encoder;

    UrlService service;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        service = new UrlService(redisTemplate, urlRepository, encoder, "http://localhost");
    }

    @Test
    void create_incrementsCounter_encodesId_savesAndCaches() {
        when(valueOps.increment("url:counter")).thenReturn(250000L);
        when(encoder.encode(250000L)).thenReturn("Hk2p");

        CreateUrlResponse response = service.create("https://example.com/path");

        verify(valueOps).increment("url:counter");
        verify(encoder).encode(250000L);
        verify(urlRepository).save(argThat(url ->
            url.getShortCode().equals("Hk2p") &&
            url.getLongUrl().equals("https://example.com/path") &&
            url.getUrlId().equals(250000L)
        ));
        verify(valueOps).set(
            eq("url:cache:Hk2p"),
            eq("https://example.com/path"),
            eq(Duration.ofSeconds(604800))
        );
        assertEquals("Hk2p", response.getShortCode());
        assertEquals("http://localhost/Hk2p", response.getShortUrl());
        assertEquals("https://example.com/path", response.getLongUrl());
        assertNotNull(response.getCreatedAt());
    }
}
