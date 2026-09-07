package com.reviewpilot.service.ai;

import com.reviewpilot.model.PrUrl;
import com.reviewpilot.service.github.GithubCodeSearcher;
import org.springframework.stereotype.Service;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Process-local search reuse and quota policy; transport belongs to GithubCodeSearcher. */
@Service
public class RepositorySearchService {
    private final GithubCodeSearcher searcher;
    private final Map<String, String> searchCache = new ConcurrentHashMap<>();
    private int searchCount;
    private long windowStart = System.currentTimeMillis();

    public RepositorySearchService(GithubCodeSearcher searcher) { this.searcher = searcher; }

    private synchronized boolean acquireSearchSlot() {
        long now = System.currentTimeMillis();
        if (now - windowStart > 60_000) {
            windowStart = now;
            searchCount = 0;
        }
        if (searchCount >= 25) return false;
        searchCount++;
        return true;
    }

    public String search(PrUrl pr, String query) {
        if (query == null || query.isBlank()) return "Error: query is required";

        String cacheKey = pr.owner() + "/" + pr.repo() + "/" + query;
        String cached = searchCache.get(cacheKey);
        if (cached != null) return "(cached) " + cached;

        // Process-local counter window; reset and increment under one lock.
        if (!acquireSearchSlot()) {
            return "Search rate limited. Please use the information you already have to continue the review.";
        }

        String result = searcher.searchCode(pr, query);
        if (result == null || result.isBlank()) {
            result = "No results found for: " + query;
        }
        searchCache.put(cacheKey, result);
        return result;
    }
}
