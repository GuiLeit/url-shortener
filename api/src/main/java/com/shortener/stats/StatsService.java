package com.shortener.stats;

import com.shortener.url.UrlRepository;
import com.shortener.web.NotFoundException;
import org.springframework.data.cassandra.core.CassandraOperations;
import org.springframework.data.cassandra.core.CassandraTemplate;
import org.springframework.data.cassandra.core.cql.CqlOperations;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

@Service
public class StatsService {

    private static final int MAX_RECENT = 100;

    private final UrlRepository urlRepository;
    private final CqlOperations cqlOperations;

    public StatsService(UrlRepository urlRepository, CassandraOperations cassandraOperations) {
        this.urlRepository = urlRepository;
        this.cqlOperations = ((CassandraTemplate) cassandraOperations).getCqlOperations();
    }

    public StatsResponse getStats(String shortCode, LocalDate from, LocalDate to) {
        if (!urlRepository.existsById(shortCode)) {
            throw new NotFoundException("Short code not found: " + shortCode);
        }
        if (ChronoUnit.DAYS.between(from, to) > 90) {
            throw new IllegalArgumentException("Date range cannot exceed 90 days");
        }

        long totalCount = cqlOperations.query(
                "SELECT count FROM shortener.access_counts WHERE short_code = ? AND day >= ? AND day <= ?",
                (row, n) -> row.getLong("count"),
                shortCode, from, to
        ).stream().mapToLong(Long::longValue).sum();

        List<RecentRequest> recentRequests = queryRecentRequests(shortCode, from, to);

        return new StatsResponse(shortCode, totalCount, recentRequests);
    }

    private List<RecentRequest> queryRecentRequests(String shortCode, LocalDate from, LocalDate to) {
        List<RecentRequest> results = new ArrayList<>();
        LocalDate current = to;
        while (!current.isBefore(from) && results.size() < MAX_RECENT) {
            for (int h = 23; h >= 0 && results.size() < MAX_RECENT; h--) {
                String bucket = current + "-" + String.format("%02d", h);
                int remaining = MAX_RECENT - results.size();
                List<RecentRequest> partial = cqlOperations.query(
                        "SELECT request_time, ip_address, user_agent, referer FROM shortener.requests_by_url WHERE short_code = ? AND time_bucket = ? LIMIT ?",
                        (row, n) -> new RecentRequest(
                                row.get("request_time", Instant.class),
                                row.getString("ip_address"),
                                row.getString("user_agent"),
                                row.getString("referer")),
                        shortCode, bucket, remaining);
                results.addAll(partial);
            }
            current = current.minusDays(1);
        }
        return results;
    }
}
