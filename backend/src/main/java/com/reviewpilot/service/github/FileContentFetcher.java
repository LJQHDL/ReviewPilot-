package com.reviewpilot.service.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.reviewpilot.model.PrUrl;
import com.reviewpilot.config.GithubProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** GitHub Contents/PR metadata adapter. Fetches and decodes source without prompt-budget policy. */
@Component
public class FileContentFetcher {

    private static final Logger log = LoggerFactory.getLogger(FileContentFetcher.class);

    private final WebClient github;
    private final GithubProperties props;

    public FileContentFetcher(@Qualifier("githubWebClient") WebClient githubWebClient,
                              GithubProperties props) {
        this.github = githubWebClient;
        this.props = props;
    }

    public String fetchHeadRef(PrUrl pr) {
        try {
            PrMetadata meta = github.get()
                    .uri(GithubApiPaths.pullRequest(pr))
                    .retrieve()
                    .bodyToMono(PrMetadata.class)
                    .block(props.timeout());
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
            return fetchAtRef(pr, filePath, ref);
        } catch (RuntimeException e) {
            log.debug("Failed to fetch {}: {}", filePath, e.toString());
            return null;
        }
    }

    /**
     * The path comes from the model, so it is added segment by segment: that both
     * encodes separators (no traversal via {@code ..}) and stops a {@code ?} inside
     * the path from replacing the {@code ref} this method intends to set.
     */
    public String fetchAtRef(PrUrl pr, String filePath, String ref) {
        String[] segments = filePath.split("/");
        try {
            FileContent fc = github.get()
                    .uri(b -> {
                        var builder = b.pathSegment("repos", pr.owner(), pr.repo(), "contents");
                        for (String segment : segments) {
                            if (!segment.isEmpty()) {
                                builder.pathSegment(segment);
                            }
                        }
                        return builder.queryParam("ref", ref).build();
                    })
                    .retrieve()
                    .bodyToMono(FileContent.class)
                    .block(props.timeout());
            if (fc == null || fc.content() == null) return null;
            byte[] decoded = Base64.getDecoder().decode(
                    fc.content().replaceAll("\\s", ""));
            return new String(decoded, StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            log.debug("Failed to fetch file {}: {}", filePath, e.toString());
            return null;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PrMetadata(Head head) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Head(String ref) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record FileContent(String content, String encoding) {}
}
