package com.shortener.config;

import org.junit.jupiter.api.Test;
import org.springframework.retry.support.RetryTemplate;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class RetryConfigTest {
    @Test
    void accessLogRetryTemplate_bean_is_not_null() {
        RetryTemplate template = new RetryConfig().accessLogRetryTemplate();
        assertNotNull(template);
    }
}
