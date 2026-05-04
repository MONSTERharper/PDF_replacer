package com.pdfreplace;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitFilter extends OncePerRequestFilter {
    private final Map<String, WindowCounter> counters = new ConcurrentHashMap<>();

    @Value("${pdfreplacer.rate-limit.requests-per-minute:60}")
    private int requestsPerMinute;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!path.startsWith("/api/")) {
            filterChain.doFilter(request, response);
            return;
        }

        String key = clientIp(request);
        Instant now = Instant.now();
        WindowCounter counter = counters.compute(key, (ignored, existing) -> {
            if (existing == null || now.isAfter(existing.windowStart.plusSeconds(60))) {
                return new WindowCounter(now, 1);
            }
            existing.count++;
            return existing;
        });

        if (counter.count > requestsPerMinute) {
            throw new RateLimitExceededException("Rate limit exceeded. Please try again in a minute.");
        }
        filterChain.doFilter(request, response);
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private static final class WindowCounter {
        private final Instant windowStart;
        private int count;

        private WindowCounter(Instant windowStart, int count) {
            this.windowStart = windowStart;
            this.count = count;
        }
    }
}
