package com.reviewpilot.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reviewpilot.model.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.UUID;
import java.util.function.LongSupplier;

/** 单进程共享身份的鉴权与审查准入；不信任客户端提交的代理 IP 头。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ApiSecurityFilter extends OncePerRequestFilter {
    private static final long WINDOW_NANOS = 60_000_000_000L;
    private final boolean enabled;
    private final byte[] tokenDigest;
    private final int reviewsPerMinute;
    private final int maxConcurrentReviews;
    private final ObjectMapper json;
    private final LongSupplier nanoTime;
    private long windowStarted;
    private int admitted;
    private int active;

    @Autowired
    public ApiSecurityFilter(
            @Value("${reviewpilot.security.enabled:false}") boolean enabled,
            @Value("${reviewpilot.security.access-token:}") String token,
            @Value("${reviewpilot.security.reviews-per-minute:10}") int reviewsPerMinute,
            @Value("${reviewpilot.security.max-concurrent-reviews:2}") int maxConcurrentReviews,
            ObjectMapper json) {
        this(enabled, token, reviewsPerMinute, maxConcurrentReviews, json, System::nanoTime);
    }

    ApiSecurityFilter(boolean enabled, String token, int reviewsPerMinute,
                      int maxConcurrentReviews, ObjectMapper json, LongSupplier nanoTime) {
        if (enabled && (token == null || !token.matches("[\\x21-\\x7E]{32,256}"))) {
            throw new IllegalArgumentException("Configure a 32-256 character access token when authentication is enabled");
        }
        if (reviewsPerMinute <= 0 || maxConcurrentReviews <= 0) {
            throw new IllegalArgumentException("Security rate and concurrency limits must be positive");
        }
        this.enabled = enabled;
        this.tokenDigest = digest(token == null ? "" : token);
        this.reviewsPerMinute = reviewsPerMinute;
        this.maxConcurrentReviews = maxConcurrentReviews;
        this.json = json;
        this.nanoTime = nanoTime;
        this.windowStarted = nanoTime.getAsLong();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Request-ID", UUID.randomUUID().toString());
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (("GET".equals(request.getMethod()) && "/api/health".equals(path))
                || CorsUtils.isPreFlightRequest(request)) {
            chain.doFilter(request, response);
            return;
        }
        if (enabled && !authenticated(request)) {
            response.setHeader("WWW-Authenticate", "Bearer");
            reject(response, 401, "Authentication required");
            return;
        }
        // 当前 POST 均为审查入口；按方法准入，避免 URL 别名绕过配额。
        if (!"POST".equals(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        long retryAfter = acquire();
        if (retryAfter > 0) {
            response.setHeader("Retry-After", Long.toString(retryAfter));
            reject(response, 429, "Review capacity exceeded; please retry later");
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            release();
        }
    }

    private boolean authenticated(HttpServletRequest request) {
        var headers = Collections.list(request.getHeaders("Authorization"));
        if (headers.size() != 1) return false;
        String header = headers.get(0);
        return header.length() >= 7 && header.length() <= 263
                && header.regionMatches(true, 0, "Bearer ", 0, 7)
                && MessageDigest.isEqual(tokenDigest, digest(header.substring(7)));
    }

    /** 原子更新窗口和在途计数，拒绝请求不占额度，不维护可无限增长的 IP 状态。 */
    private synchronized long acquire() {
        long now = nanoTime.getAsLong();
        long elapsed = now - windowStarted;
        if (elapsed >= WINDOW_NANOS) {
            windowStarted = now;
            admitted = 0;
            elapsed = 0;
        }
        if (admitted >= reviewsPerMinute) {
            return Math.max(1, (WINDOW_NANOS - elapsed + 999_999_999L) / 1_000_000_000L);
        }
        if (active >= maxConcurrentReviews) return 1;
        admitted++;
        active++;
        return 0;
    }

    private synchronized void release() {
        active--;
    }

    private void reject(HttpServletResponse response, int status, String error) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        json.writeValue(response.getWriter(), ErrorResponse.of(error));
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
