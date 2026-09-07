package com.reviewpilot.service.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reviewpilot.model.PrUrl;
import com.reviewpilot.service.github.FileContentFetcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class RiskFileContextLoaderTest {
    @Test
    void enrichment_uses_one_head_lookup_and_limits_prompt_content() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(response("{\"head\":{\"ref\":\"feature\"}}"));
            String source = "x".repeat(8_000) + "TAIL";
            String encoded = Base64.getEncoder().encodeToString(source.getBytes(StandardCharsets.UTF_8));
            server.enqueue(response(new ObjectMapper().writeValueAsString(Map.of("content", encoded))));
            server.enqueue(new MockResponse().setResponseCode(404));
            var fetcher = new FileContentFetcher(WebClient.builder()
                    .baseUrl(server.url("/").toString()).build());
            var loader = new RiskFileContextLoader(fetcher, true);
            Map<String, String> result = loader.fetchForRiskyFiles(new PrUrl("o", "r", 1),
                    List.of("A.java", "missing.java"));
            assertEquals(1, result.size());
            assertTrue(result.get("A.java").startsWith("x".repeat(8_000)));
            assertFalse(result.get("A.java").contains("TAIL"));
            assertTrue(result.get("A.java").contains("truncated 4 chars"));
            assertEquals("/repos/o/r/pulls/1", server.takeRequest().getPath());
            assertEquals("/repos/o/r/contents/A.java?ref=feature", server.takeRequest().getPath());
            assertEquals("/repos/o/r/contents/missing.java?ref=feature", server.takeRequest().getPath());
        }
    }

    @Test
    void disabled_enrichment_performs_no_network_calls() throws Exception {
        try (var server = new MockWebServer()) {
            var fetcher = new FileContentFetcher(WebClient.builder()
                    .baseUrl(server.url("/").toString()).build());
            assertTrue(new RiskFileContextLoader(fetcher, false)
                    .fetchForRiskyFiles(new PrUrl("o", "r", 1), List.of("A.java")).isEmpty());
            assertEquals(0, server.getRequestCount());
        }
    }

    private static MockResponse response(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }
}
