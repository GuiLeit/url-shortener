package com.shortener.stats;

import java.util.List;

public class StatsResponse {

    private final String shortCode;
    private final long totalCount;
    private final List<RecentRequest> recentRequests;

    public StatsResponse(String shortCode, long totalCount, List<RecentRequest> recentRequests) {
        this.shortCode = shortCode;
        this.totalCount = totalCount;
        this.recentRequests = recentRequests;
    }

    public String getShortCode()                   { return shortCode; }
    public long getTotalCount()                    { return totalCount; }
    public List<RecentRequest> getRecentRequests() { return recentRequests; }
}
