package com.shortener.event;

import java.time.Instant;

public class AccessEvent {

    private final String shortCode;
    private final Instant requestTime;
    private final String ipAddress;
    private final String userAgent;
    private final String referer;

    public AccessEvent(String shortCode, Instant requestTime, String ipAddress,
                       String userAgent, String referer) {
        this.shortCode = shortCode;
        this.requestTime = requestTime;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
        this.referer = referer;
    }

    public String getShortCode()    { return shortCode; }
    public Instant getRequestTime() { return requestTime; }
    public String getIpAddress()    { return ipAddress; }
    public String getUserAgent()    { return userAgent; }
    public String getReferer()      { return referer; }
}
