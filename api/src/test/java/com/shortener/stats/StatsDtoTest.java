package com.shortener.stats;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class StatsDtoTest {

    @Test
    void stats_response_stores_fields() {
        RecentRequest req = new RecentRequest(Instant.parse("2026-05-25T10:00:00Z"), "1.2.3.4", "ua", "ref");
        StatsResponse resp = new StatsResponse("abc1", 42L, List.of(req));
        assertEquals("abc1", resp.getShortCode());
        assertEquals(42L, resp.getTotalCount());
        assertEquals(1, resp.getRecentRequests().size());
        assertEquals("1.2.3.4", resp.getRecentRequests().get(0).getIpAddress());
    }
}
