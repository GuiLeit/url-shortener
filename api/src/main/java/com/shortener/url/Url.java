package com.shortener.url;

import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKey;
import org.springframework.data.cassandra.core.mapping.Table;

import java.time.Instant;

@Table("urls_by_shortcode")
public class Url {

    @PrimaryKey("short_code")
    private String shortCode;

    @Column("long_url")
    private String longUrl;

    @Column("url_id")
    private Long urlId;

    @Column("created_at")
    private Instant createdAt;

    public Url() {}

    public Url(String shortCode, String longUrl, Long urlId, Instant createdAt) {
        this.shortCode = shortCode;
        this.longUrl = longUrl;
        this.urlId = urlId;
        this.createdAt = createdAt;
    }

    public String getShortCode() { return shortCode; }
    public String getLongUrl() { return longUrl; }
    public Long getUrlId() { return urlId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setShortCode(String shortCode) { this.shortCode = shortCode; }
    public void setLongUrl(String longUrl) { this.longUrl = longUrl; }
    public void setUrlId(Long urlId) { this.urlId = urlId; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
