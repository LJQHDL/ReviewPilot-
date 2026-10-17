package com.reviewpilot.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link OpenAiCompatibleProvider} 的 HTTP 契约测试。
 * 端点信息（base-url/key/model）经 {@link ModelEndpoint} 入参传入，provider 自行拼地址与鉴权头。
 */
@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
class OpenAiCompatibleProviderTest {

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

    /** 构造指向 MockWebServer 的端点。 */
    private ModelEndpoint endpoint(String key) {
        return new ModelEndpoint("mock", server.url("/").toString(), key, "test-model",
                Duration.ofSeconds(5), 256, 0.2, 1);
    }

    /** provider 只拿到一个裸 Builder（无 baseUrl、无鉴权头）；地址/Bearer 由 provider 按端点现拼。 */
    private OpenAiCompatibleProvider provider() {
        WebClient.Builder b = WebClient.builder()
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        return new OpenAiCompatibleProvider(b);
    }

    @Test
    void posts_correct_body_and_headers_then_extracts_content() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .setBody("""
                        {
                          "id": "chatcmpl-1",
                          "model": "test-model",
                          "choices": [
                            {
                              "index": 0,
                              "finish_reason": "stop",
                              "message": {"role": "assistant", "content": "{\\"summary\\":\\"ok\\"}"}
                            }
                          ]
                        }
                        """));

        String reply = provider().complete(endpoint("sk-test-1234"), "you are reviewer", "diff goes here");
        assertEquals("{\"summary\":\"ok\"}", reply);

        RecordedRequest req = server.takeRequest();
        assertEquals("/v1/chat/completions", req.getPath());
        // Bearer 头来自端点，非 Builder 预设 —— 证明 provider 按 ep 现拼鉴权
        assertEquals("Bearer sk-test-1234", req.getHeader("Authorization"));
        assertNotNull(req.getHeader("Content-Type"));
        assertTrue(req.getHeader("Content-Type").contains("application/json"));

        JsonNode body = new ObjectMapper().readTree(req.getBody().readUtf8());
        assertEquals("test-model", body.get("model").asText());
        assertEquals(false, body.get("stream").asBoolean(true));
        assertEquals("system", body.get("messages").get(0).get("role").asText());
        assertEquals("you are reviewer", body.get("messages").get(0).get("content").asText());
        assertEquals("user", body.get("messages").get(1).get("role").asText());
        assertEquals("diff goes here", body.get("messages").get(1).get("content").asText());
    }

    @Test
    void tool_arguments_round_trip_quotes_newlines_and_nested_values() throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}"));
        var arguments = Map.<String, Object>of(
                "query", "name=\"quoted\"\nC:\\src", "options", Map.of("limit", 2));
        provider().chat(endpoint("test-key"), List.of(
                Message.assistant("", List.of(new ToolCall("c1", "search_repo", arguments))),
                Message.tool("c1", "done")), List.of());
        ObjectMapper mapper = new ObjectMapper();
        JsonNode body = mapper.readTree(server.takeRequest().getBody().readUtf8());
        JsonNode decoded = mapper.readTree(body.path("messages").get(0)
                .path("tool_calls").get(0).path("function").path("arguments").asText());
        assertEquals("name=\"quoted\"\nC:\\src", decoded.path("query").asText());
        assertEquals(2, decoded.path("options").path("limit").asInt());
    }

    @Test
    void without_key_throws_clear_error_without_calling_api() {
        AiProviderException e = assertThrows(AiProviderException.class,
                () -> provider().complete(endpoint(""), "sys", "user"));
        assertTrue(e.getMessage().contains("API key is not set"));
        // 缺 key 应在发起网络调用前拦截
        assertEquals(0, server.getRequestCount());
    }

    @Test
    void http_error_is_wrapped_in_AiProviderException() {
        server.enqueue(new MockResponse().setResponseCode(401).setBody("{\"error\":\"bad token\"}"));
        AiProviderException e = assertThrows(AiProviderException.class,
                () -> provider().complete(endpoint("sk-bad"), "sys", "user"));
        assertTrue(e.getMessage().contains("401"));
    }

    @Test
    void upstreamErrorBodyIsNotLogged(org.springframework.boot.test.system.CapturedOutput output) {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("secret-marker"));
        assertThrows(AiProviderException.class,
                () -> provider().chat(endpoint("test-key"), List.of(Message.user("review")), List.of()));
        assertFalse(output.getAll().contains("secret-marker"),
                "upstream response bodies may carry credentials and must not reach the log");
    }

    @Test
    void empty_choices_throws_AiProviderException() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .setBody("""
                        {"id":"x","model":"test-model","choices":[]}
                        """));
        AiProviderException e = assertThrows(AiProviderException.class,
                () -> provider().complete(endpoint("sk-test"), "sys", "user"));
        assertTrue(e.getMessage().toLowerCase().contains("no choices"));
    }

    // --- ModelEndpoint 的脱敏/配置判定（原属 DeepSeekProperties，现搬到端点值对象）---

    @Test
    void redacted_key_never_returns_full_value() {
        ModelEndpoint ep = new ModelEndpoint("deepseek", "https://api.deepseek.com",
                "sk-supersecretkey-012345", "deepseek-chat",
                Duration.ofSeconds(60), 4096, 0.2, 1);
        assertEquals("****2345", ep.redactedKey());
        assertFalse(ep.redactedKey().contains("supersecret"));
    }

    @Test
    void unset_key_renders_as_unset_marker() {
        ModelEndpoint ep = new ModelEndpoint("x", "https://api.x", null, "m",
                null, 0, -1, -1);
        assertEquals("(unset)", ep.redactedKey());
        assertFalse(ep.isConfigured());
    }

    @Test
    void short_key_renders_as_four_stars_only() {
        ModelEndpoint ep = new ModelEndpoint("x", "https://api.x", "abcd", "m",
                Duration.ofSeconds(60), 4096, 0.2, 1);
        assertEquals("****", ep.redactedKey());
    }

    @Test
    void defaults_fill_in_when_illegal_values_passed() {
        // maxTokens<=0 / temperature<0 / maxRetries<0 → 紧凑构造器兜底默认值
        ModelEndpoint ep = new ModelEndpoint("x", "https://api.x", "k", "m",
                null, 0, -1, -1);
        assertEquals(4096, ep.maxTokens());
        assertEquals(0.2, ep.temperature());
        assertEquals(1, ep.maxRetries());
        assertEquals(Duration.ofSeconds(60), ep.timeout());
    }
}
