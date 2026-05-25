package com.shortener.redirect;

import com.shortener.event.AccessEvent;
import com.shortener.event.AccessEventPublisher;
import com.shortener.url.UrlRepository;
import com.shortener.web.NotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

@Service
public class RedirectService {

    private static final int MAX_LOCK_RETRIES = 3;
    private static final long LOCK_SLEEP_MS = 50;

    private final StringRedisTemplate redisTemplate;
    private final org.springframework.data.redis.core.ValueOperations<String, String> valueOps;
    private final UrlRepository urlRepository;
    private final AccessEventPublisher publisher;
    private final long cacheTtlSeconds;
    private final long negCacheTtlSeconds;

    public RedirectService(StringRedisTemplate redisTemplate,
                           UrlRepository urlRepository,
                           AccessEventPublisher publisher,
                           @Value("${shortener.cache-ttl-seconds}") long cacheTtlSeconds,
                           @Value("${shortener.negcache-ttl-seconds}") long negCacheTtlSeconds) {
        this.redisTemplate = redisTemplate;
        this.valueOps = redisTemplate.opsForValue();
        this.urlRepository = urlRepository;
        this.publisher = publisher;
        this.cacheTtlSeconds = cacheTtlSeconds;
        this.negCacheTtlSeconds = negCacheTtlSeconds;
    }

    public String redirect(String shortcode, String ip, String userAgent, String referer) {
        // 1. Negative cache
        if (Boolean.TRUE.equals(redisTemplate.hasKey("url:negcache:" + shortcode))) {
            throw new NotFoundException("Short code not found: " + shortcode);
        }

        // 2. Positive cache
        String cached = valueOps.get("url:cache:" + shortcode);
        if (cached != null) {
            publishEvent(shortcode, ip, userAgent, referer);
            return cached;
        }

        // 3. Stampede lock with retries
        for (int attempt = 0; attempt < MAX_LOCK_RETRIES; attempt++) {
            Boolean locked = valueOps
                    .setIfAbsent("url:lock:" + shortcode, "1", Duration.ofSeconds(5));
            if (Boolean.TRUE.equals(locked)) {
                return lookupAndCache(shortcode, ip, userAgent, referer, true);
            }
            // Lock contention — sleep and retry cache read
            try { Thread.sleep(LOCK_SLEEP_MS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            String retryCache = valueOps.get("url:cache:" + shortcode);
            if (retryCache != null) {
                publishEvent(shortcode, ip, userAgent, referer);
                return retryCache;
            }
        }

        // 4. Fall through to direct DB read (lock exhausted)
        return lookupAndCache(shortcode, ip, userAgent, referer, false);
    }

    private String lookupAndCache(String shortcode, String ip, String userAgent, String referer, boolean holdingLock) {
        return urlRepository.findById(shortcode).map(url -> {
            valueOps.set(
                "url:cache:" + shortcode, url.getLongUrl(), Duration.ofSeconds(cacheTtlSeconds));
            if (holdingLock) redisTemplate.delete("url:lock:" + shortcode);
            publishEvent(shortcode, ip, userAgent, referer);
            return url.getLongUrl();
        }).orElseGet(() -> {
            valueOps.set(
                "url:negcache:" + shortcode, "0", Duration.ofSeconds(negCacheTtlSeconds));
            if (holdingLock) redisTemplate.delete("url:lock:" + shortcode);
            throw new NotFoundException("Short code not found: " + shortcode);
        });
    }

    private void publishEvent(String shortcode, String ip, String userAgent, String referer) {
        publisher.publish(new AccessEvent(shortcode, Instant.now(), ip, userAgent, referer));
    }
}
