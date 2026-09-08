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
 * search_repo 工具的复用与配额策略层（HTTP 传输属于 {@link GithubCodeSearcher}）。
 *
 * <p>限流预算与缓存都按仓库维度分片且设有上限：进程级单一计数器曾让一个高频 PR
 * 耗尽全部配额、饿死其他并发评审的 search_repo，缓存也会随模型自选的键无限增长。
 */
@Service
public class RepositorySearchService {

    private static final long WINDOW_MILLIS = 60_000L;
    /** 同时跟踪的仓库数上限；被 LRU 逐出的仓库只是重新开窗，不影响正确性。 */
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

    /** 构造线程安全的 LRU Map（accessOrder=true + removeEldestEntry 实现逐出）。 */
    private static <V> Map<String, V> lru(int max) {
        return Collections.synchronizedMap(new LinkedHashMap<>(32, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
                return size() > max;
            }
        });
    }

    /** 执行一次仓库代码搜索：参数校验 → 查缓存 → 按仓库限流 → 调上游 → 回填缓存。 */
    public String search(PrUrl pr, String query) {
        if (query == null || query.isBlank()) return "Error: query is required";
        // 花括号能通过 URI 编码，但 WebClient 仍会把它们当作模板占位符解析，
        // 因此直接拒绝而不是转义。
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
            // 限流时返回提示文本而非抛异常，让 LLM 基于已有信息继续评审
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
     * 单个仓库的固定窗口计数器。刻意做成尽力而为：输掉重置竞争只会多放行一次调用，
     * 而该限制的意义是保护配额，不是精确计数。
     */
    private static final class Window {
        private final AtomicLong startedAt = new AtomicLong(System.currentTimeMillis());
        private final AtomicInteger count = new AtomicInteger();

        /** 窗口过期则原子地重置计数，然后占用一个名额；超额返回 false。 */
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
