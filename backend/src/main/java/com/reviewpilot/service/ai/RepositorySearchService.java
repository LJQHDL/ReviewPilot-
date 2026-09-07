package com.reviewpilot.service.ai;

import com.reviewpilot.model.PrUrl;
import com.reviewpilot.service.github.GithubCodeSearcher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Search reuse and quota policy; transport belongs to {@link GithubCodeSearcher}.
 *
 * <p>Both the budget and the cache are scoped <em>per repository</em> and bounded.
 * A single process-wide counter let one busy PR spend the entire allowance and
 * starve every concurrent review of {@code search_repo}, and the cache grew
 * forever on keys the model chooses.
 */
@Service
public class RepositorySearchService {

    private static final long WINDOW_MILLIS = 60_000L;
    /** Repositories tracked at once; an evicted window simply starts fresh. */
    private static final int MAX_TRACKED_REPOS = 512;

    private final GithubCodeSearcher searcher;
    private final int limitPerMinute;
    private final Map<String, String> searchCache;
    private final Map<String, Window> searchWindows;

    public RepositorySearchService(
            GithubCodeSearcher searcher,
            @Value("${reviewpilot.agent.tools.search-limit-per-minute:25}") int limitPerMinute,
            @Value("${reviewpilot.agent.tools.search-cache-entries:256}") int cacheEntries) {
        this.searcher = searcher;
        this.limitPerMinute = limitPerMinute <= 0 ? 25 : limitPerMinute;
        this.searchCache = lru(cacheEntries <= 0 ? 256 : cacheEntries);
        this.searchWindows = lru(MAX_TRACKED_REPOS);
    }

    private static <V> Map<String, V> lru(int max) {
        return Collections.synchronizedMap(new LinkedHashMap<>(32, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
                return size() > max;
            }
        });
    }

    public String search(PrUrl pr, String query) {
        if (query == null || query.isBlank()) return "Error: query is required";
        // Braces survive URI encoding yet WebClient would still read them as
        // template placeholders, so they are refused rather than escaped.
        if (query.indexOf('{') >= 0 || query.indexOf('}') >= 0) {
            return "Error: query must not contain '{' or '}'";
        }

        String repo = pr.owner() + "/" + pr.repo();
        String cached = searchCache.get(repo + "/" + query);
        if (cached != null) return "(cached) " + cached;

        Window window;
        synchronized (searchWindows) {
            window = searchWindows.computeIfAbsent(repo, k -> new Window());
        }
        if (!window.acquire(WINDOW_MILLIS, limitPerMinute)) {
            return "Search rate limited. Please use the information you already have to continue the review.";
        }

        String result = searcher.searchCode(pr, query);
        if (result == null || result.isBlank()) {
            result = "No results found for: " + query;
        }
        searchCache.put(repo + "/" + query, result);
        return result;
    }

    /**
     * Fixed window for one repository. Best-effort by design: losing a reset race
     * costs a single call against a limit that exists to protect quota, not to be exact.
     */
    private static final class Window {
        private final AtomicLong startedAt = new AtomicLong(System.currentTimeMillis());
        private final AtomicInteger count = new AtomicInteger();

        boolean acquire(long windowMillis, int limit) {
            long now = System.currentTimeMillis();
            long start = startedAt.get();
            if (now - start > windowMillis && startedAt.compareAndSet(start, now)) {
                count.set(0);
            }
            return count.incrementAndGet() <= limit;
        }
    }
}
