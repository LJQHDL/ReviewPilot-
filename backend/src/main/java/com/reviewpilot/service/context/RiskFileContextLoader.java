package com.reviewpilot.service.context;

import com.reviewpilot.model.PrUrl;
import com.reviewpilot.service.github.FileContentFetcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Optional batch enrichment and prompt-size policy for rule-flagged files. */
@Component
public class RiskFileContextLoader {
    private static final Logger log = LoggerFactory.getLogger(RiskFileContextLoader.class);
    private static final int MAX_CONTENT_CHARS = 8_000;
    private final FileContentFetcher fetcher;
    private final boolean enabled;

    public RiskFileContextLoader(FileContentFetcher fetcher,
            @Value("${reviewpilot.agent.content-fetcher.enabled:true}") boolean enabled) {
        this.fetcher = fetcher;
        this.enabled = enabled;
    }

    public Map<String, String> fetchForRiskyFiles(PrUrl pr, List<String> riskyFilePaths) {
        if (!enabled) return Map.of();
        Map<String, String> results = new HashMap<>();
        String ref = fetcher.fetchHeadRef(pr);
        if (ref == null || ref.isBlank()) {
            log.warn("Could not determine PR head ref; skipping full-file fetching");
            return results;
        }
        for (String path : riskyFilePaths) {
            try {
                String content = fetcher.fetchAtRef(pr, path, ref);
                if (content != null) {
                    results.put(path, truncate(content));
                }
            } catch (RuntimeException e) {
                log.debug("Failed to fetch content for {}: {}", path, e.toString());
            }
        }
        return results;
    }

    private static String truncate(String s) {
        if (s.length() <= MAX_CONTENT_CHARS) return s;
        return s.substring(0, MAX_CONTENT_CHARS)
                + "\n\n[...truncated " + (s.length() - MAX_CONTENT_CHARS) + " chars]";
    }

}
