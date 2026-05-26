package com.shortener.consumer;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AccessLogRepositoryTest {
    @Test
    void repository_interface_compiles() {
        assertNotNull(AccessLogRepository.class);
    }
}
