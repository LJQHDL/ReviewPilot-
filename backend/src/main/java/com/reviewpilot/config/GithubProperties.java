package com.reviewpilot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code reviewpilot.github.*} section of application.yml.
 *
 * @param apiBase Base URL of the GitHub REST API (override for GitHub Enterprise).
 * @param token   Personal access token. Empty string means anonymous (60 req/h).
 */
@ConfigurationProperties("reviewpilot.github")
public record GithubProperties(String apiBase, String token) {

    public GithubProperties {
        if (apiBase == null || apiBase.isBlank()) {
            apiBase = "https://api.github.com";
        }
        if (token == null) {
            token = "";
        }
    }
}
