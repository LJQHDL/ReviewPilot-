package com.reviewpilot.service.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.reviewpilot.config.DeepSeekProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.List;
import java.util.Map;

/**
 * DeepSeek implementation of {@link ModelProvider}, calling the OpenAI-compatible
 * {@code POST /v1/chat/completions} endpoint.
 * <p>
 * Configuration (env-driven, see {@link DeepSeekProperties}):
 * <pre>
 *   reviewpilot.ai.deepseek.api-key:   ${DEEPSEEK_API_KEY:}     # NEVER commit
 *   reviewpilot.ai.deepseek.model:     deepseek-chat
 *   reviewpilot.ai.deepseek.api-base:  https://api.deepseek.com
 * </pre>
 * The API key is read from the env var; logs only ever show
 * {@link DeepSeekProperties#redactedKey()}.
 */
@Component
public class DeepSeekProvider implements ModelProvider {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekProvider.class);

    private final WebClient webClient;
    private final DeepSeekProperties props;

    public DeepSeekProvider(@Qualifier("deepSeekWebClient") WebClient webClient,
                            DeepSeekProperties props) {
        this.webClient = webClient;
        this.props = props;
        log.info("DeepSeekProvider ready: model={}, apiBase={}, key={}",
                props.model(), props.apiBase(), props.redactedKey());
    }

    @Override
    public String name() {
        return "deepseek";
    }

    @Override
    public String modelName() {
        return props.model();
    }

    @Override
    public AgentResponse chat(List<Message> messages, List<Tool> tools) {
        if (!props.isConfigured()) {
            throw new AiAuthenticationException(
                    "DeepSeek API key is not set. Export DEEPSEEK_API_KEY before calling /api/review.");
        }

        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("model", props.model());
        body.put("temperature", props.temperature());
        body.put("max_tokens", props.maxTokens());
        body.put("stream", false);
        body.put("messages", messages.stream().map(OpenAiMessageMapper::message).toList());
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools.stream().map(OpenAiMessageMapper::tool).toList());
        }

        DsCompletion response = callWithRetry(body, "chat");
        if (response == null) {
            throw new AiProviderException("DeepSeek chat call failed after retries");
        }

        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            throw new AiProviderException("DeepSeek returned no choices");
        }
        DsChoice first = response.choices().get(0);
        if (first.message() == null) {
            throw new AiProviderException("DeepSeek response missing message");
        }

        String content = first.message().content();
        List<ToolCall> toolCalls = first.message().toolCalls() != null
                ? first.message().toolCalls().stream().map(DsToolCall::toToolCall).toList()
                : List.of();
        int promptTokens = response.usage() != null ? response.usage().promptTokens() : 0;
        int completionTokens = response.usage() != null ? response.usage().completionTokens() : 0;

        return new AgentResponse(
                content != null ? content : "",
                toolCalls != null ? toolCalls : List.of(),
                promptTokens, completionTokens);
    }

    @Override
    public String complete(String systemPrompt, String userPrompt) {
        if (!props.isConfigured()) {
            throw new AiAuthenticationException(
                    "DeepSeek API key is not set. Export DEEPSEEK_API_KEY before calling /api/review.");
        }

        Map<String, Object> body = Map.of(
                "model", props.model(),
                "temperature", props.temperature(),
                "max_tokens", props.maxTokens(),
                "stream", false,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)
                )
        );

        DsCompletion response = callWithRetry(body, "complete");
        if (response == null) {
            throw new AiProviderException("DeepSeek complete call failed after retries");
        }

        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            throw new AiProviderException("DeepSeek returned no choices");
        }
        DsChoice first = response.choices().get(0);
        if (first.message() == null || first.message().content() == null) {
            throw new AiProviderException("DeepSeek response missing message content");
        }
        return first.message().content();
    }

    /**
     * Call the DeepSeek API with up to 2 retries for transient network errors
     * (Connection reset, timeout). Non-transient errors (4xx, 5xx with status)
     * are NOT retried and fail immediately.
     */
    private DsCompletion callWithRetry(Map<String, Object> body, String caller) {
        // Cost of one call is timeout x (maxRetries + 1) plus backoff, and nothing can
        // preempt it once started — these two numbers decide how real the request
        // budget in ReviewPipeline is.
        int maxRetries = props.maxRetries();
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return webClient.post()
                        .uri("/v1/chat/completions")
                        .bodyValue(body)
                        .retrieve()
                        .bodyToMono(DsCompletion.class)
                        .block();
            } catch (WebClientResponseException e) {
                // HTTP status errors (4xx/5xx) — not transient, don't retry
                String respBody = e.getResponseBodyAsString();
                log.warn("DeepSeek {} call failed: status={} body={}",
                        caller, e.getStatusCode().value(),
                        respBody != null ? respBody.substring(0, Math.min(respBody.length(), 500)) : "null");
                throw new AiProviderException(
                        "DeepSeek API call failed with status " + e.getStatusCode().value(), e);
            } catch (RuntimeException e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                boolean isTransient = msg.contains("reset") || msg.contains("timeout")
                        || msg.contains("timed out") || msg.contains("refused");
                if (attempt < maxRetries && isTransient) {
                    long delay = (attempt + 1) * 1000L;
                    log.warn("DeepSeek {} transient error (attempt {}/{}): {} — retrying in {}s",
                            caller, attempt + 1, maxRetries + 1, msg, delay / 1000);
                    try { Thread.sleep(delay); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); break; }
                    continue;
                }
                if (attempt >= maxRetries) {
                    log.error("DeepSeek {} failed after {} retries: {}", caller, maxRetries, msg);
                }
                throw new AiProviderException("DeepSeek API call failed: " + msg, e);
            }
        }
        return null;
    }

    // --- DTOs (subset of the OpenAI-compatible schema) -----------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DsCompletion(String id, String model, List<DsChoice> choices,
                                  DsUsage usage) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DsChoice(int index, DsMessage message, String finish_reason) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DsMessage(String role, String content,
                             @com.fasterxml.jackson.annotation.JsonProperty("tool_calls")
                             List<DsToolCall> toolCalls) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DsToolCall(String id, String type, DsFunction function) {
        public ToolCall toToolCall() {
            return new ToolCall(id, function.name(),
                    parseArguments(function.arguments()));
        }
        private static Map<String, Object> parseArguments(String json) {
            if (json == null || json.isBlank()) return Map.of();
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue(json, Map.class);
                return map;
            } catch (Exception e) {
                return Map.of();
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DsFunction(String name, String arguments) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DsUsage(@com.fasterxml.jackson.annotation.JsonProperty("prompt_tokens") int promptTokens,
                           @com.fasterxml.jackson.annotation.JsonProperty("completion_tokens") int completionTokens) {}
}
