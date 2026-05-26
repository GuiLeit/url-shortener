package com.shortener.stats;

import jakarta.validation.constraints.Pattern;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.ZoneOffset;

@RestController
@RequestMapping("/api/v1/urls")
@Validated
public class StatsController {

    private final StatsService statsService;

    public StatsController(StatsService statsService) {
        this.statsService = statsService;
    }

    @GetMapping("/{shortcode}/stats")
    public StatsResponse getStats(
            @PathVariable @Pattern(regexp = "[A-Za-z0-9]{1,11}") String shortcode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        LocalDate resolvedTo = to != null ? to : LocalDate.now(ZoneOffset.UTC);
        LocalDate resolvedFrom = from != null ? from : resolvedTo.minusDays(7);
        return statsService.getStats(shortcode, resolvedFrom, resolvedTo);
    }
}
