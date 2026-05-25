package com.shortener.redirect;

import com.shortener.event.AccessEvent;
import com.shortener.event.AccessEventPublisher;
import com.shortener.url.UrlRepository;
import com.shortener.web.NotFoundException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
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

    private final MeterRegistry meterRegistry;
    private final Counter cacheHitPositive;
    private final Counter cacheHitNegative;
    private final Counter cacheMiss;
    private final Counter redirectHit;
    private final Counter redirectMiss;
    private final Counter redirectNotFound;
    private final Timer redirectLatencyTimer;

    public RedirectService(StringRedisTemplate redisTemplate,
                           UrlRepository urlRepository,
                           AccessEventPublisher publisher,
                           @Value("${shortener.cache-ttl-seconds}") long cacheTtlSeconds,
                           @Value("${shortener.negcache-ttl-seconds}") long negCacheTtlSeconds,
                           MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.valueOps = redisTemplate.opsForValue();
        this.urlRepository = urlRepository;
        this.publisher = publisher;
        this.cacheTtlSeconds = cacheTtlSeconds;
        this.negCacheTtlSeconds = negCacheTtlSeconds;
        this.meterRegistry = meterRegistry;
        this.cacheHitPositive = Counter.builder("shortener.cache.hits").tag("type", "positive").register(meterRegistry);
        this.cacheHitNegative = Counter.builder("shortener.cache.hits").tag("type", "negative").register(meterRegistry);
        this.cacheMiss = Counter.builder("shortener.cache.misses").register(meterRegistry);
        this.redirectHit = Counter.builder("shortener.redirects").tag("result", "hit").register(meterRegistry);
        this.redirectMiss = Counter.builder("shortener.redirects").tag("result", "miss").register(meterRegistry);
        this.redirectNotFound = Counter.builder("shortener.redirects").tag("result", "notfound").register(meterRegistry);
        this.redirectLatencyTimer = Timer.builder("shortener.redirect.latency")
                .description("End-to-end redirect request latency")
                .publishPercentileHistogram()
                .register(meterRegistry);
    }

    public String redirect(String shortcode, String ip, String userAgent, String referer) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            return doRedirect(shortcode, ip, userAgent, referer);
        } finally {
            sample.stop(redirectLatencyTimer);
        }
    }

    private String doRedirect(String shortcode, String ip, String userAgent, String referer) {
        // 1. Negative cache
        if (Boolean.TRUE.equals(redisTemplate.hasKey("url:negcache:" + shortcode))) {
            cacheHitNegative.increment();
            redirectNotFound.increment();
            throw new NotFoundException("Short code not found: " + shortcode);
        }

        // 2. Positive cache
        String cached = valueOps.get("url:cache:" + shortcode);
        if (cached != null) {
            cacheHitPositive.increment();
            redirectHit.increment();
            publishEvent(shortcode, ip, userAgent, referer);
            return cached;
        }

        // 3. Cache miss — going to DB
        cacheMiss.increment();

        // 4. Stampede lock with retries
        for (int attempt = 0; attempt < MAX_LOCK_RETRIES; attempt++) {
            Boolean locked = valueOps.setIfAbsent("url:lock:" + shortcode, "1", Duration.ofSeconds(5));
            if (Boolean.TRUE.equals(locked)) {
                return lookupAndCache(shortcode, ip, userAgent, referer, true);
            }
            try { Thread.sleep(LOCK_SLEEP_MS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            String retryCache = valueOps.get("url:cache:" + shortcode);
            if (retryCache != null) {
                cacheHitPositive.increment();
                redirectHit.increment();
                publishEvent(shortcode, ip, userAgent, referer);
                return retryCache;
            }
        }

        // 5. Fall through to direct DB read (lock exhausted)
        return lookupAndCache(shortcode, ip, userAgent, referer, false);
    }

    private String lookupAndCache(String shortcode, String ip, String userAgent, String referer, boolean holdingLock) {
        return urlRepository.findById(shortcode).map(url -> {
            valueOps.set("url:cache:" + shortcode, url.getLongUrl(), Duration.ofSeconds(cacheTtlSeconds));
            if (holdingLock) redisTemplate.delete("url:lock:" + shortcode);
            redirectMiss.increment();
            publishEvent(shortcode, ip, userAgent, referer);
            return url.getLongUrl();
        }).orElseGet(() -> {
            valueOps.set("url:negcache:" + shortcode, "0", Duration.ofSeconds(negCacheTtlSeconds));
            if (holdingLock) redisTemplate.delete("url:lock:" + shortcode);
            redirectNotFound.increment();
            throw new NotFoundException("Short code not found: " + shortcode);
        });
    }

    private void publishEvent(String shortcode, String ip, String userAgent, String referer) {
        publisher.publish(new AccessEvent(shortcode, Instant.now(), ip, userAgent, referer));
    }
}
