package com.reviewpilot.service.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.reviewpilot.model.PrUrl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fetches the full content of files from a PR's head branch via the GitHub
 * Contents API. Used to give the LLM wider context than the diff hunk ±3 lines.
 *
 * <p>Only triggered for files that already have rule-detected risks, so the
 * extra API cost is proportional to finding count rather than file count.
 */
@Component
public class FileContentFetcher {

    private static final Logger log = LoggerFactory.getLogger(FileContentFetcher.class);
    private static final int MAX_CONTENT_CHARS = 8_000;

    private final WebClient github;
    private final boolean enabled;

    public FileContentFetcher(WebClient githubWebClient,
                               @Value("${reviewpilot.agent.content-fetcher.enabled:true}") boolean enabled) {
        this.github = githubWebClient;
        this.enabled = enabled;
    }

    public Map<String, String> fetchForRiskyFiles(PrUrl pr, List<String> riskyFilePaths) {
        if (!enabled) return Map.of();
        Map<String, String> results = new HashMap<>();
        String ref = fetchHeadRef(pr);
        if (ref == null || ref.isBlank()) {
            log.warn("Could not determine PR head ref; skipping full-file fetching");
            return results;
        }
        for (String path : riskyFilePaths) {
            try {
                String content = fetchFileContent(pr, path, ref);
                if (content != null) {
                    results.put(path, truncate(content));
                }
            } catch (RuntimeException e) {
                log.debug("Failed to fetch content for {}: {}", path, e.toString());
            }
        }
        return results;
    }

    private String fetchHeadRef(PrUrl pr) {
        try {
            PrMetadata meta = github.get()
                    .uri(pr.apiPath())
                    .retrieve()
                    .bodyToMono(PrMetadata.class)
                    .block();
            if (meta != null && meta.head() != null && meta.head().ref() != null) {
                return meta.head().ref();
            }
        } catch (RuntimeException e) {
            log.warn("Failed to fetch PR metadata for {}/{}: {}", pr.owner(), pr.repo(), e.toString());
        }
        return null;
    }

    /**
     * Public entry point for ToolRegistry — fetch a single file's content.
     * Returns null if the file is not found or not accessible.
     */
    public String fetchContent(PrUrl pr, String filePath) {
        String ref = fetchHeadRef(pr);
        if (ref == null || ref.isBlank()) return null;
        try {
            return fetchFileContent(pr, filePath, ref);
        } catch (RuntimeException e) {
            log.debug("Failed to fetch {}: {}", filePath, e.toString());
            return null;
        }
    }

    private String fetchFileContent(PrUrl pr, String filePath, String ref) {
        // WebClient encodes the path — use uri() with placeholders to avoid double-encoding
        String uri = "/repos/" + pr.owner() + "/" + pr.repo()
                + "/contents/" + filePath + "?ref=" + ref;
        try {
            FileContent fc = github.get()
                    .uri(uri)
                    .retrieve()
                    .bodyToMono(FileContent.class)
                    .block();
            if (fc == null || fc.content() == null) return null;
            byte[] decoded = Base64.getDecoder().decode(
                    fc.content().replaceAll("\\s", ""));
            return new String(decoded, StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            log.debug("Failed to fetch file {}: {}", filePath, e.toString());
            return null;
        }
    }

    private static String truncate(String s) {
        if (s.length() <= MAX_CONTENT_CHARS) return s;
        return s.substring(0, MAX_CONTENT_CHARS)
                + "\n\n[...truncated " + (s.length() - MAX_CONTENT_CHARS) + " chars]";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PrMetadata(Head head) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Head(String ref) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record FileContent(String content, String encoding) {}
}
