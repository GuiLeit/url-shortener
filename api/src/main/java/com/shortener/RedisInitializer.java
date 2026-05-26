package com.shortener;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisInitializer {

    private final StringRedisTemplate redisTemplate;

    public RedisInitializer(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initializeCounter() {
        redisTemplate.opsForValue().setIfAbsent("url:counter", "250000");
    }
}
