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

    public UrlService(StringRedisTemplate redisTemplate,
                      UrlRepository urlRepository,
                      Base62Encoder encoder,
                      @Value("${shortener.base-url}") String baseUrl) {
        this.redisTemplate = redisTemplate;
        this.urlRepository = urlRepository;
        this.encoder = encoder;
        this.baseUrl = baseUrl;
    }

    public CreateUrlResponse create(String longUrl) {
        long id = redisTemplate.opsForValue().increment("url:counter");
        String code = encoder.encode(id);
        Instant now = Instant.now();
        urlRepository.save(new Url(code, longUrl, id, now));
        redisTemplate.opsForValue().set("url:cache:" + code, longUrl, Duration.ofSeconds(604800));
        return new CreateUrlResponse(code, baseUrl + "/" + code, longUrl, now);
    }
}
