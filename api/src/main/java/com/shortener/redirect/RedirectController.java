package com.shortener.redirect;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@Validated
@RestController
public class RedirectController {

    private final RedirectService redirectService;

    public RedirectController(RedirectService redirectService) {
        this.redirectService = redirectService;
    }

    @GetMapping("/{shortcode}")
    public ResponseEntity<Void> redirect(@PathVariable @Pattern(regexp = "[A-Za-z0-9]{1,11}") String shortcode,
                                         HttpServletRequest request) {
        String ip = extractIp(request);
        String userAgent = request.getHeader("User-Agent");
        String referer = request.getHeader("Referer");

        String longUrl = redirectService.redirect(shortcode, ip, userAgent, referer);

        return ResponseEntity.status(302)
                .location(URI.create(longUrl))
                .header("Cache-Control", "no-store, max-age=0")
                .build();
    }

    private String extractIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
