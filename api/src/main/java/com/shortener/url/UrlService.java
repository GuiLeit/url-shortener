package com.shortener.url;

import com.shortener.encoding.Base62Encoder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

@Service
public class UrlService {

    private final StringRedisTemplate redisTemplate;
    private final UrlRepository urlRepository;
    private final Base62Encoder encoder;
    private final String baseUrl;
    private final long cacheTtlSeconds;

    public UrlService(StringRedisTemplate redisTemplate,
                      UrlRepository urlRepository,
                      Base62Encoder encoder,
                      @Value("${shortener.base-url}") String baseUrl,
                      @Value("${shortener.cache-ttl-seconds}") long cacheTtlSeconds) {
        this.redisTemplate = redisTemplate;
        this.urlRepository = urlRepository;
        this.encoder = encoder;
        this.baseUrl = baseUrl;
        this.cacheTtlSeconds = cacheTtlSeconds;
    }

    public CreateUrlResponse create(String longUrl) {
        Long rawId = redisTemplate.opsForValue().increment("url:counter");
        if (rawId == null) throw new IllegalStateException("Redis counter returned null");
        long id = rawId;
        String code = encoder.encode(id);
        Instant now = Instant.now();
        urlRepository.save(new Url(code, longUrl, id, now));
        redisTemplate.opsForValue().set("url:cache:" + code, longUrl, Duration.ofSeconds(cacheTtlSeconds));
        return new CreateUrlResponse(code, baseUrl + "/" + code, longUrl, now);
    }
}
