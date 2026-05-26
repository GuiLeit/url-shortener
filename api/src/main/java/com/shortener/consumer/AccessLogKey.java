package com.shortener.consumer;

import org.springframework.data.cassandra.core.cql.Ordering;
import org.springframework.data.cassandra.core.cql.PrimaryKeyType;
import org.springframework.data.cassandra.core.mapping.PrimaryKeyClass;
import org.springframework.data.cassandra.core.mapping.PrimaryKeyColumn;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

@PrimaryKeyClass
public class AccessLogKey implements Serializable {

    @PrimaryKeyColumn(name = "short_code", ordinal = 0, type = PrimaryKeyType.PARTITIONED)
    private String shortCode;

    @PrimaryKeyColumn(name = "time_bucket", ordinal = 1, type = PrimaryKeyType.PARTITIONED)
    private String timeBucket;

    @PrimaryKeyColumn(name = "request_time", ordinal = 2, type = PrimaryKeyType.CLUSTERED, ordering = Ordering.DESCENDING)
    private Instant requestTime;

    @PrimaryKeyColumn(name = "request_id", ordinal = 3, type = PrimaryKeyType.CLUSTERED)
    private UUID requestId;

    public AccessLogKey() {}

    public AccessLogKey(String shortCode, String timeBucket, Instant requestTime, UUID requestId) {
        this.shortCode = shortCode;
        this.timeBucket = timeBucket;
        this.requestTime = requestTime;
        this.requestId = requestId;
    }

    public String getShortCode()    { return shortCode; }
    public String getTimeBucket()   { return timeBucket; }
    public Instant getRequestTime() { return requestTime; }
    public UUID getRequestId()      { return requestId; }

    public void setShortCode(String v)    { this.shortCode = v; }
    public void setTimeBucket(String v)   { this.timeBucket = v; }
    public void setRequestTime(Instant v) { this.requestTime = v; }
    public void setRequestId(UUID v)      { this.requestId = v; }
}
