package com.shortener.stats;

import java.time.Instant;

public class RecentRequest {

    private final Instant requestTime;
    private final String ipAddress;
    private final String userAgent;
    private final String referer;

    public RecentRequest(Instant requestTime, String ipAddress, String userAgent, String referer) {
        this.requestTime = requestTime;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
        this.referer = referer;
    }

    public Instant getRequestTime() { return requestTime; }
    public String getIpAddress()    { return ipAddress; }
    public String getUserAgent()    { return userAgent; }
    public String getReferer()      { return referer; }
}
