package com.reviewpilot.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PrUrlTest {

    @Test
    void parses_canonical_url() {
        PrUrl pr = PrUrl.parse("https://github.com/spring-projects/spring-boot/pull/12345");
        assertEquals("spring-projects", pr.owner());
        assertEquals("spring-boot", pr.repo());
        assertEquals(12345, pr.number());
        assertEquals("/repos/spring-projects/spring-boot/pulls/12345", com.reviewpilot.service.github.GithubApiPaths.pullRequest(pr));
    }

    @Test
    void tolerates_whitespace_and_trailing_slash() {
        PrUrl pr = PrUrl.parse("  https://github.com/foo/bar/pull/7/  ");
        assertEquals("foo", pr.owner());
        assertEquals("bar", pr.repo());
        assertEquals(7, pr.number());
    }

    @Test
    void tolerates_query_and_fragment() {
        PrUrl pr = PrUrl.parse("https://github.com/foo/bar/pull/42?diff=split#issue");
        assertEquals(42, pr.number());
    }

    @Test
    void accepts_uppercase_host() {
        PrUrl pr = PrUrl.parse("https://GitHub.com/foo/bar/pull/1");
        assertEquals("foo", pr.owner());
    }

    @Test
    void rejects_non_github_host() {
        assertThrows(IllegalArgumentException.class,
                () -> PrUrl.parse("https://gitlab.com/foo/bar/pull/1"));
    }

    @Test
    void rejects_issue_url() {
        assertThrows(IllegalArgumentException.class,
                () -> PrUrl.parse("https://github.com/foo/bar/issues/1"));
    }

    @Test
    void rejects_missing_number() {
        assertThrows(IllegalArgumentException.class,
                () -> PrUrl.parse("https://github.com/foo/bar/pull/"));
    }

    @Test
    void rejects_non_integer_number() {
        assertThrows(IllegalArgumentException.class,
                () -> PrUrl.parse("https://github.com/foo/bar/pull/abc"));
    }

    @Test
    void rejects_zero_or_negative() {
        assertThrows(IllegalArgumentException.class,
                () -> PrUrl.parse("https://github.com/foo/bar/pull/0"));
    }

    @Test
    void rejects_blank() {
        assertThrows(IllegalArgumentException.class, () -> PrUrl.parse(""));
        assertThrows(IllegalArgumentException.class, () -> PrUrl.parse(null));
    }

    @Test
    void rejects_garbage() {
        assertThrows(IllegalArgumentException.class, () -> PrUrl.parse("not a url"));
    }
}
