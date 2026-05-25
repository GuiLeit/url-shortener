package com.shortener.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;

@Component
public class NonRecursiveUrlValidator implements ConstraintValidator<NonRecursiveUrl, String> {

    @Value("${shortener.base-url:http://localhost}")
    private String baseUrl;

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) return true;
        try {
            String inputHost = URI.create(value).getHost();
            String shortenerHost = URI.create(baseUrl).getHost();
            return !inputHost.equalsIgnoreCase(shortenerHost);
        } catch (Exception e) {
            return false;
        }
    }
}
