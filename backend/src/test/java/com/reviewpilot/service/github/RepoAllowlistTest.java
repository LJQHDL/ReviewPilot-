package com.reviewpilot.service.github;

import com.reviewpilot.config.GithubProperties;
import com.reviewpilot.model.PrUrl;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Locks the bound on which repositories this service reads with its own token.
 * An empty allowlist is deliberately permissive; the moment an anonymous caller
 * can name any repo, this is what stops the service acting as their deputy.
 */
class RepoAllowlistTest {

    private static RepoAllowlist allowlist(String... patterns) {
        return new RepoAllowlist(new GithubProperties("https://api.github.com", "tok",
                List.of(patterns), null, 0));
    }

    private static PrUrl pr(String url) {
        return PrUrl.parse(url);
    }

    @Test
    void empty_allowlist_admits_anything() {
        RepoAllowlist open = new RepoAllowlist(
                new GithubProperties("https://api.github.com", "", null, null, 0));

        assertDoesNotThrow(() -> open.requireAllowed(pr("https://github.com/any/one/pull/1")));
    }

    @Test
    void exact_entry_admits_only_that_repository() {
        RepoAllowlist list = allowlist("octocat/Hello-World");

        assertDoesNotThrow(() -> list.requireAllowed(pr("https://github.com/octocat/Hello-World/pull/7")));
        assertThrows(RepoNotAllowedException.class,
                () -> list.requireAllowed(pr("https://github.com/octocat/Other/pull/7")));
    }

    @Test
    void wildcard_covers_an_org_or_a_users_repositories() {
        RepoAllowlist list = allowlist("my-org/*", "trusted-user/repo-*");

        assertDoesNotThrow(() -> list.requireAllowed(pr("https://github.com/my-org/anything/pull/1")));
        assertDoesNotThrow(() -> list.requireAllowed(pr("https://github.com/trusted-user/repo-core/pull/1")));
        assertThrows(RepoNotAllowedException.class,
                () -> list.requireAllowed(pr("https://github.com/other-org/anything/pull/1")));
    }

    /**
     * Matching is anchored: an entry of "octocat/Hello" must not admit
     * "octocat/Hello-World", or a narrow allowlist silently widens by prefix.
     */
    @Test
    void entries_match_wholly_not_by_prefix() {
        RepoAllowlist list = allowlist("octocat/Hello");

        assertThrows(RepoNotAllowedException.class,
                () -> list.requireAllowed(pr("https://github.com/octocat/Hello-World/pull/1")));
    }

    @Test
    void regex_metacharacters_in_an_entry_are_literal() {
        RepoAllowlist list = allowlist("a.b/repo");

        assertDoesNotThrow(() -> list.requireAllowed(pr("https://github.com/a.b/repo/pull/1")));
        assertThrows(RepoNotAllowedException.class,
                () -> list.requireAllowed(pr("https://github.com/axb/repo/pull/1")));
    }
}
