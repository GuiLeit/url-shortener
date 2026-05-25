package com.shortener.config;

import com.shortener.encoding.Base62Encoder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EncoderConfig {

    @Bean
    public Base62Encoder base62Encoder(@Value("${shortener.secret-key}") String secretKey) {
        return new Base62Encoder(secretKey);
    }
}
