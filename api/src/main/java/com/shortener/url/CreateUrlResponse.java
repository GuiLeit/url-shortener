package com.shortener.url;

import java.time.Instant;

public class CreateUrlResponse {

    private final String shortCode;
    private final String shortUrl;
    private final String longUrl;
    private final Instant createdAt;

    public CreateUrlResponse(String shortCode, String shortUrl, String longUrl, Instant createdAt) {
        this.shortCode = shortCode;
        this.shortUrl = shortUrl;
        this.longUrl = longUrl;
        this.createdAt = createdAt;
    }

    public String getShortCode() { return shortCode; }
    public String getShortUrl() { return shortUrl; }
    public String getLongUrl() { return longUrl; }
    public Instant getCreatedAt() { return createdAt; }
}
