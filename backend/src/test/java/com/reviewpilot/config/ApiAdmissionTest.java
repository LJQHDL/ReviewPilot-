package com.reviewpilot.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class ApiAdmissionTest {
    private static final String TOKEN = "0123456789abcdef0123456789abcdef";

    private MockHttpServletRequest request() {
        var request = new MockHttpServletRequest("POST", "/api/review");
        request.addHeader("Authorization", "Bearer " + TOKEN);
        return request;
    }

    @Test
    void releasesPermitAfterFailureAndRenewsRateWindow() throws Exception {
        var time = new AtomicLong();
        var filter = new ApiSecurityFilter(true, TOKEN, 2, 1, new ObjectMapper(), time::get);
        assertThrows(ServletException.class, () -> filter.doFilter(request(), new MockHttpServletResponse(),
                (req, res) -> { throw new ServletException("failure"); }));
        var second = new MockHttpServletResponse();
        filter.doFilter(request(), second, (req, res) -> res.getWriter().write("admitted"));
        assertEquals("admitted", second.getContentAsString());
        var excess = new MockHttpServletResponse();
        filter.doFilter(request(), excess, (req, res) -> fail("over-budget request reached pipeline"));
        assertEquals(429, excess.getStatus());
        assertEquals("60", excess.getHeader("Retry-After"));
        time.set(60_000_000_000L);
        var renewed = new MockHttpServletResponse();
        filter.doFilter(request(), renewed, (req, res) -> res.getWriter().write("renewed"));
        assertEquals("renewed", renewed.getContentAsString());
    }

    @Test
    void rejectsConcurrentReviewAndDoesNotResetActiveCountWithWindow() throws Exception {
        var time = new AtomicLong();
        var filter = new ApiSecurityFilter(true, TOKEN, 10, 1, new ObjectMapper(), time::get);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(() -> {
                filter.doFilter(request(), new MockHttpServletResponse(), (req, res) -> {
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) throw new ServletException("test timeout");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new ServletException(e);
                    }
                });
                return null;
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            time.set(60_000_000_000L);
            var blocked = new MockHttpServletResponse();
            filter.doFilter(request(), blocked, (req, res) -> fail("concurrency cap bypassed"));
            assertEquals(429, blocked.getStatus());
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
            var next = new MockHttpServletResponse();
            filter.doFilter(request(), next, (req, res) -> res.getWriter().write("released"));
            assertEquals("released", next.getContentAsString());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }
}
