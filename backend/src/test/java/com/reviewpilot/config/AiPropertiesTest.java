package com.reviewpilot.config;

import com.reviewpilot.service.ai.ModelEndpoint;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** F3 生效端点确定规则 + F6 启动期校验，直接在 {@link AiProperties} 层验证。 */
class AiPropertiesTest {

    private static AiProperties.EndpointSpec spec(String apiBase, String key, String model) {
        return new AiProperties.EndpointSpec(apiBase, key, model,
                Duration.ofSeconds(30), 1024, 0.3, 2);
    }

    @Test
    void single_endpoint_with_no_active_is_adopted() {
        var props = new AiProperties(null, Map.of("solo", spec("https://a", "k", "m1")));
        ModelEndpoint ep = props.resolveActive();
        assertEquals("solo", ep.name());
        assertEquals("m1", ep.model());
    }

    @Test
    void explicit_active_selects_that_endpoint() {
        var eps = new LinkedHashMap<String, AiProperties.EndpointSpec>();
        eps.put("deepseek", spec("https://d", "kd", "deepseek-chat"));
        eps.put("kimi", spec("https://k", "kk", "moonshot"));
        ModelEndpoint ep = new AiProperties("kimi", eps).resolveActive();
        assertEquals("kimi", ep.name());
        assertEquals("moonshot", ep.model());
    }

    @Test
    void multiple_endpoints_without_active_fails() {
        var eps = new LinkedHashMap<String, AiProperties.EndpointSpec>();
        eps.put("a", spec("https://a", "k", "m"));
        eps.put("b", spec("https://b", "k", "m"));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new AiProperties(null, eps).resolveActive());
        assertTrue(e.getMessage().contains("multiple"));
    }

    @Test
    void active_naming_undeclared_endpoint_fails_and_lists_available() {
        var eps = Map.of("deepseek", spec("https://d", "k", "m"));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new AiProperties("nope", eps).resolveActive());
        assertTrue(e.getMessage().contains("nope"));
        assertTrue(e.getMessage().contains("deepseek"));
    }

    @Test
    void missing_api_base_fails_locating_the_endpoint() {
        var eps = Map.of("broken", spec("  ", "k", "m"));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new AiProperties("broken", eps).resolveActive());
        assertTrue(e.getMessage().contains("broken"));
        assertTrue(e.getMessage().contains("api-base"));
    }

    @Test
    void no_endpoints_at_all_fails() {
        assertThrows(IllegalStateException.class,
                () -> new AiProperties(null, Map.of()).resolveActive());
        assertThrows(IllegalStateException.class,
                () -> new AiProperties(null, null).resolveActive());
    }

    @Test
    void missing_generation_params_fall_back_to_endpoint_defaults() {
        var bare = new AiProperties.EndpointSpec("https://a", "k", null, null, null, null, null);
        ModelEndpoint ep = new AiProperties(null, Map.of("a", bare)).resolveActive();
        // model 缺省时用 default-<name>；其余走 ModelEndpoint 紧凑构造器默认
        assertEquals("default-a", ep.model());
        assertEquals(4096, ep.maxTokens());
        assertTrue(ep.isConfigured());
    }
}
