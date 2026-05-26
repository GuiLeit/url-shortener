package com.shortener.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
public class AccessEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(AccessEventPublisher.class);

    private final RabbitTemplate rabbitTemplate;

    public AccessEventPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @Async("accessEventExecutor")
    public void publish(AccessEvent event) {
        try {
            rabbitTemplate.convertAndSend("url.events", "url.accessed", event);
        } catch (Exception e) {
            log.warn("Failed to publish access event for {}: {}", event.getShortCode(), e.getMessage());
        }
    }
}
