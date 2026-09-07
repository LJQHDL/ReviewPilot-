package com.reviewpilot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Binds the {@code reviewpilot.github.*} section of application.yml.
 *
 * @param apiBase      Base URL of the GitHub REST API (override for GitHub Enterprise).
 * @param token        Personal access token. Empty string means anonymous (60 req/h).
 * @param allowedRepos {@code owner/repo} patterns this service will act on; empty means
 *                     any repository. Every GitHub call is made with the server's own
 *                     token on behalf of an anonymous caller, so this is the bound on
 *                     what that token can be pointed at.
 * @param timeout      Per-call HTTP ceiling. Every GitHub call in this app blocks a
 *                     servlet thread, so this is what keeps one stalled connection from
 *                     pinning a worker forever.
 * @param maxFilePages How many {@code /pulls/{n}/files} pages (100 files each) to walk
 *                     before giving up. Hitting the cap surfaces as a truncated review
 *                     rather than a silently partial one.
 */
@ConfigurationProperties("reviewpilot.github")
public record GithubProperties(String apiBase, String token, List<String> allowedRepos,
                               Duration timeout, int maxFilePages) {

    public GithubProperties {
        if (apiBase == null || apiBase.isBlank()) {
            apiBase = "https://api.github.com";
        }
        if (token == null) {
            token = "";
        }
        if (allowedRepos == null) {
            allowedRepos = List.of();
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            timeout = Duration.ofSeconds(10);
        }
        if (maxFilePages <= 0) {
            maxFilePages = 3;
        }
    }
}
