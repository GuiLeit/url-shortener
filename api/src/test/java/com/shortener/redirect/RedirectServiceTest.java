package com.shortener.redirect;

import com.shortener.event.AccessEventPublisher;
import com.shortener.url.Url;
import com.shortener.url.UrlRepository;
import com.shortener.web.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RedirectServiceTest {

    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOps;
    @Mock UrlRepository urlRepository;
    @Mock AccessEventPublisher publisher;

    RedirectService service;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        service = new RedirectService(redisTemplate, urlRepository, publisher, 604800L, 60L);
    }

    @Test
    void negcache_hit_throws_notFound_without_db() {
        when(redisTemplate.hasKey("url:negcache:abc")).thenReturn(true);

        assertThrows(NotFoundException.class, () ->
            service.redirect("abc", "1.2.3.4", "TestAgent", null));

        verify(urlRepository, never()).findById(any());
        verify(publisher, never()).publish(any());
    }

    @Test
    void poscache_hit_returns_url_without_db() {
        when(redisTemplate.hasKey("url:negcache:abc")).thenReturn(false);
        when(valueOps.get("url:cache:abc")).thenReturn("https://example.com");

        String result = service.redirect("abc", "1.2.3.4", "TestAgent", null);

        assertEquals("https://example.com", result);
        verify(urlRepository, never()).findById(any());
        verify(publisher).publish(argThat(e ->
            e.getShortCode().equals("abc") &&
            e.getIpAddress().equals("1.2.3.4")
        ));
    }

    @Test
    void cache_miss_db_hit_populates_cache_and_returns_url() {
        when(redisTemplate.hasKey("url:negcache:abc")).thenReturn(false);
        when(valueOps.get("url:cache:abc")).thenReturn(null);
        when(valueOps.setIfAbsent(eq("url:lock:abc"), eq("1"), any())).thenReturn(true);
        Url url = new Url("abc", "https://example.com", 250001L, Instant.now());
        when(urlRepository.findById("abc")).thenReturn(Optional.of(url));

        String result = service.redirect("abc", "1.2.3.4", "TestAgent", null);

        assertEquals("https://example.com", result);
        verify(valueOps).set(eq("url:cache:abc"), eq("https://example.com"), any());
        verify(redisTemplate).delete("url:lock:abc");
        verify(publisher).publish(any());
    }

    @Test
    void cache_miss_db_miss_sets_negcache_and_throws() {
        when(redisTemplate.hasKey("url:negcache:abc")).thenReturn(false);
        when(valueOps.get("url:cache:abc")).thenReturn(null);
        when(valueOps.setIfAbsent(eq("url:lock:abc"), eq("1"), any())).thenReturn(true);
        when(urlRepository.findById("abc")).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () ->
            service.redirect("abc", "1.2.3.4", "TestAgent", null));

        verify(valueOps).set(eq("url:negcache:abc"), eq("0"), any());
        verify(redisTemplate).delete("url:lock:abc");
        verify(publisher, never()).publish(any());
    }

    @Test
    void lock_contention_retries_cache_and_hits_on_second_check() {
        when(redisTemplate.hasKey("url:negcache:abc")).thenReturn(false);
        when(valueOps.get("url:cache:abc")).thenReturn(null).thenReturn("https://example.com");
        when(valueOps.setIfAbsent(eq("url:lock:abc"), eq("1"), any())).thenReturn(false);

        String result = service.redirect("abc", "1.2.3.4", "TestAgent", null);

        assertEquals("https://example.com", result);
        verify(urlRepository, never()).findById(any());
        verify(publisher).publish(any());
    }
}
