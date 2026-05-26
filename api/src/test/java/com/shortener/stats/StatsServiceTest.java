package com.shortener.stats;

import com.shortener.url.UrlRepository;
import com.shortener.web.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.cassandra.core.CassandraTemplate;
import org.springframework.data.cassandra.core.cql.CqlOperations;
import org.springframework.data.cassandra.core.cql.RowMapper;

import java.time.LocalDate;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StatsServiceTest {

    @Mock UrlRepository urlRepository;
    @Mock CassandraTemplate cassandraTemplate;
    @Mock CqlOperations cqlOperations;

    StatsService statsService;

    @BeforeEach
    void setUp() {
        when(cassandraTemplate.getCqlOperations()).thenReturn(cqlOperations);
        statsService = new StatsService(urlRepository, cassandraTemplate);
    }

    @Test
    void shortcode_not_found_throws_not_found() {
        when(urlRepository.existsById("missing")).thenReturn(false);
        assertThrows(NotFoundException.class, () ->
            statsService.getStats("missing", LocalDate.of(2026, 5, 18), LocalDate.of(2026, 5, 25)));
    }

    @Test
    void date_range_over_90_days_throws_illegal_argument() {
        when(urlRepository.existsById("abc1")).thenReturn(true);
        assertThrows(IllegalArgumentException.class, () ->
            statsService.getStats("abc1", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 5, 25)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void total_count_is_sum_of_access_count_rows() {
        LocalDate from = LocalDate.of(2026, 5, 18);
        LocalDate to = LocalDate.of(2026, 5, 25);
        when(urlRepository.existsById("abc1")).thenReturn(true);
        lenient().doAnswer(inv -> {
            String cql = inv.getArgument(0);
            return cql.contains("access_counts") ? List.of(100L, 50L) : List.of();
        }).when(cqlOperations).query(anyString(), any(RowMapper.class), any(Object[].class));

        StatsResponse resp = statsService.getStats("abc1", from, to);
        assertEquals(150L, resp.getTotalCount());
    }

    @Test
    @SuppressWarnings("unchecked")
    void recent_requests_returned_from_requests_by_url() {
        LocalDate from = LocalDate.of(2026, 5, 25);
        LocalDate to = LocalDate.of(2026, 5, 25);
        when(urlRepository.existsById("abc1")).thenReturn(true);
        RecentRequest req = new RecentRequest(Instant.now(), "1.2.3.4", "ua", null);
        lenient().doAnswer(inv -> {
            String cql = inv.getArgument(0);
            return cql.contains("requests_by_url") ? List.of(req) : List.of();
        }).when(cqlOperations).query(anyString(), any(RowMapper.class), any(Object[].class));

        StatsResponse resp = statsService.getStats("abc1", from, to);
        assertFalse(resp.getRecentRequests().isEmpty());
        assertEquals("1.2.3.4", resp.getRecentRequests().get(0).getIpAddress());
    }
}
