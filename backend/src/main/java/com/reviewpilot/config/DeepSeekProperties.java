package com.reviewpilot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Binds {@code reviewpilot.ai.deepseek.*} from application.yml.
 * <p>
 * The API key MUST come from the {@code DEEPSEEK_API_KEY} environment variable
 * (yml uses {@code ${DEEPSEEK_API_KEY:}}). Don't commit a key value to yml,
 * env files, or tests — see README "Secrets" section.
 *
 * @param apiBase     Override for self-hosted gateways. Defaults to
 *                    https://api.deepseek.com.
 * @param apiKey      The bearer token. Empty = provider is disabled and
 *                    {@code /api/review} returns a clear error instead of crashing.
 * @param model       DeepSeek model id (e.g. deepseek-chat, deepseek-reasoner).
 * @param timeout     HTTP read timeout. Defaults to 60s — code analysis on
 *                    large PRs is slow.
 * @param maxTokens   Output token cap. 4096 covers JSON for most PRs.
 * @param temperature Sampling temperature. 0.2 keeps the JSON output stable.
 */
@ConfigurationProperties("reviewpilot.ai.deepseek")
public record DeepSeekProperties(
        String apiBase,
        String apiKey,
        String model,
        Duration timeout,
        Integer maxTokens,
        Double temperature
) {

    public DeepSeekProperties {
        if (apiBase == null || apiBase.isBlank()) {
            apiBase = "https://api.deepseek.com";
        }
        if (apiKey == null) {
            apiKey = "";
        }
        if (model == null || model.isBlank()) {
            model = "deepseek-chat";
        }
        if (timeout == null) {
            timeout = Duration.ofSeconds(60);
        }
        if (maxTokens == null || maxTokens <= 0) {
            maxTokens = 4096;
        }
        if (temperature == null || temperature < 0) {
            temperature = 0.2;
        }
    }

    public boolean isConfigured() {
        return !apiKey.isBlank();
    }

    /**
     * Returns a redacted form of the key for logs ({@code ****<last4>}).
     * Never log the full {@link #apiKey()}.
     */
    public String redactedKey() {
        if (apiKey.isBlank()) {
            return "(unset)";
        }
        if (apiKey.length() <= 4) {
            return "****";
        }
        return "****" + apiKey.substring(apiKey.length() - 4);
    }
}
