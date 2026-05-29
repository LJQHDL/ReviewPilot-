package com.reviewpilot.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reviewpilot.config.DeepSeekProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeepSeekProviderTest {

    private MockWebServer server;

    @BeforeEach
    void start() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stop() throws Exception {
        server.shutdown();
    }

    private DeepSeekProvider providerWithKey(String key) {
        DeepSeekProperties props = new DeepSeekProperties(
                server.url("/").toString(), key, "deepseek-chat",
                Duration.ofSeconds(5), 256, 0.2);
        WebClient.Builder b = WebClient.builder()
                .baseUrl(server.url("/").toString())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        if (props.isConfigured()) {
            b.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + key);
        }
        return new DeepSeekProvider(b.build(), props);
    }

    @Test
    void posts_correct_body_and_headers_then_extracts_content() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .setBody("""
                        {
                          "id": "chatcmpl-1",
                          "model": "deepseek-chat",
                          "choices": [
                            {
                              "index": 0,
                              "finish_reason": "stop",
                              "message": {"role": "assistant", "content": "{\\"summary\\":\\"ok\\"}"}
                            }
                          ]
                        }
                        """));

        DeepSeekProvider p = providerWithKey("sk-test-1234");
        String reply = p.complete("you are reviewer", "diff goes here");
        assertEquals("{\"summary\":\"ok\"}", reply);

        RecordedRequest req = server.takeRequest();
        assertEquals("/v1/chat/completions", req.getPath());
        assertEquals("Bearer sk-test-1234", req.getHeader("Authorization"));
        assertNotNull(req.getHeader("Content-Type"));
        assertTrue(req.getHeader("Content-Type").contains("application/json"));

        // Verify the request body contains the messages, model, and stream=false flag.
        JsonNode body = new ObjectMapper().readTree(req.getBody().readUtf8());
        assertEquals("deepseek-chat", body.get("model").asText());
        assertEquals(false, body.get("stream").asBoolean(true));
        assertEquals("system", body.get("messages").get(0).get("role").asText());
        assertEquals("you are reviewer", body.get("messages").get(0).get("content").asText());
        assertEquals("user", body.get("messages").get(1).get("role").asText());
        assertEquals("diff goes here", body.get("messages").get(1).get("content").asText());
    }

    @Test
    void without_key_throws_clear_error_without_calling_api() {
        DeepSeekProvider p = providerWithKey("");
        AiProviderException e = assertThrows(AiProviderException.class,
                () -> p.complete("sys", "user"));
        assertTrue(e.getMessage().startsWith("DeepSeek API key is not set"));
        // No request should have been recorded.
        assertEquals(0, server.getRequestCount());
    }

    @Test
    void http_error_is_wrapped_in_AiProviderException() {
        server.enqueue(new MockResponse().setResponseCode(401).setBody("{\"error\":\"bad token\"}"));
        DeepSeekProvider p = providerWithKey("sk-bad");
        AiProviderException e = assertThrows(AiProviderException.class,
                () -> p.complete("sys", "user"));
        assertTrue(e.getMessage().contains("401"));
    }

    @Test
    void empty_choices_throws_AiProviderException() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .setBody("""
                        {"id":"x","model":"deepseek-chat","choices":[]}
                        """));
        DeepSeekProvider p = providerWithKey("sk-test");
        AiProviderException e = assertThrows(AiProviderException.class,
                () -> p.complete("sys", "user"));
        assertTrue(e.getMessage().toLowerCase().contains("no choices"));
    }

    @Test
    void redacted_key_is_used_in_logs_and_never_full_value() {
        // Property-level test: redactedKey() never returns the full key.
        DeepSeekProperties props = new DeepSeekProperties(
                "https://api.deepseek.com",
                "sk-supersecretkey-012345",
                "deepseek-chat",
                Duration.ofSeconds(60),
                4096, 0.2);
        String redacted = props.redactedKey();
        assertEquals("****2345", redacted);
        assertTrue(!redacted.contains("supersecret"));
    }

    @Test
    void unset_key_renders_as_unset_marker() {
        DeepSeekProperties props = new DeepSeekProperties(null, null, null, null, null, null);
        assertEquals("(unset)", props.redactedKey());
        assertEquals(false, props.isConfigured());
    }

    @Test
    void short_key_renders_as_four_stars_only() {
        DeepSeekProperties props = new DeepSeekProperties(
                "https://api.deepseek.com", "abcd", "deepseek-chat",
                Duration.ofSeconds(60), 4096, 0.2);
        assertEquals("****", props.redactedKey());
    }
}
