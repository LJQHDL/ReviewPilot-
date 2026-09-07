package com.reviewpilot.model;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Objects;

/**
 * Represents a parsed GitHub Pull Request URL.
 * Accepts URLs like {@code https://github.com/owner/repo/pull/12} (with optional
 * trailing slash, anchor, query, or surrounding whitespace).
 */
public record PrUrl(String owner, String repo, int number) {

    public PrUrl {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(repo, "repo");
        if (owner.isBlank() || repo.isBlank()) {
            throw new IllegalArgumentException("owner/repo must not be blank");
        }
        if (number <= 0) {
            throw new IllegalArgumentException("PR number must be positive, got " + number);
        }
    }

    public static PrUrl parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("PR URL must not be empty");
        }
        URI uri;
        try {
            uri = new URI(raw.trim());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Not a valid URL: " + raw, e);
        }

        String host = uri.getHost();
        if (host == null || !host.equalsIgnoreCase("github.com")) {
            throw new IllegalArgumentException("Only github.com PR URLs are supported, got host: " + host);
        }

        String path = uri.getPath();
        if (path == null) {
            throw new IllegalArgumentException("Missing path in PR URL: " + raw);
        }
        // Expect /<owner>/<repo>/pull/<number>
        String[] parts = path.split("/");
        // parts[0] is empty because path starts with '/'
        if (parts.length < 5 || !"pull".equals(parts[3])) {
            throw new IllegalArgumentException(
                    "Expected path /<owner>/<repo>/pull/<number>, got: " + path);
        }
        String owner = parts[1];
        String repo = parts[2];
        int number;
        try {
            number = Integer.parseInt(parts[4]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("PR number must be an integer, got: " + parts[4], e);
        }
        return new PrUrl(owner, repo, number);
    }

}
