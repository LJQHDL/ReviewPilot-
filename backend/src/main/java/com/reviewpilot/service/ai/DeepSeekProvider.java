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
    public String complete(String systemPrompt, String userPrompt) {
        if (!props.isConfigured()) {
            throw new AiProviderException(
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

        ChatCompletion response;
        try {
            response = webClient.post()
                    .uri("/v1/chat/completions")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(ChatCompletion.class)
                    .block();
        } catch (WebClientResponseException e) {
            // Body might echo back parts of the request; log status + length only.
            log.warn("DeepSeek API call failed: status={} bodyLen={}",
                    e.getStatusCode().value(),
                    e.getResponseBodyAsString() == null ? 0 : e.getResponseBodyAsString().length());
            throw new AiProviderException(
                    "DeepSeek API call failed with status " + e.getStatusCode().value(), e);
        } catch (RuntimeException e) {
            throw new AiProviderException("DeepSeek API call failed: " + e.getMessage(), e);
        }

        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            throw new AiProviderException("DeepSeek returned no choices");
        }
        Choice first = response.choices().get(0);
        if (first.message() == null || first.message().content() == null) {
            throw new AiProviderException("DeepSeek response missing message content");
        }
        return first.message().content();
    }

    // --- DTOs (subset of the OpenAI-compatible schema) -----------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChatCompletion(String id, String model, List<Choice> choices) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Choice(int index, Message message, String finish_reason) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(String role, String content) {}
}
