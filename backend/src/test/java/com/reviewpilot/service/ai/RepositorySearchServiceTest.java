package com.reviewpilot.service.ai;

import com.reviewpilot.model.PrUrl;
import com.reviewpilot.service.github.GithubCodeSearcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The search budget and its cache are scoped per repository and bounded. Both
 * were previously process-wide: one busy PR could spend the allowance for every
 * concurrent review, and the cache grew without limit on keys the model chooses.
 */
class RepositorySearchServiceTest {

    private static final PrUrl REPO_A = new PrUrl("owner", "alpha", 1);
    private static final PrUrl REPO_B = new PrUrl("owner", "beta", 2);

    private GithubCodeSearcher searcherReturning(String hit) {
        GithubCodeSearcher searcher = mock(GithubCodeSearcher.class);
        when(searcher.searchCode(any(), anyString())).thenReturn(hit);
        return searcher;
    }

    @Test
    void one_repository_cannot_exhaust_another_s_budget() {
        GithubCodeSearcher searcher = searcherReturning("hit\n");
        RepositorySearchService service = new RepositorySearchService(searcher, 1, 256);

        String first = service.search(REPO_A, "a1");
        String second = service.search(REPO_A, "a2");
        String otherRepo = service.search(REPO_B, "b1");

        assertEquals("hit", first.trim());
        assertTrue(second.contains("rate limited"), "second call in the same repo window");
        assertEquals("hit", otherRepo.trim(),
                "a different PR must not inherit this repository's spent budget");
        verify(searcher, times(2)).searchCode(any(), anyString());
    }

    @Test
    void cache_evicts_rather_than_growing_forever() {
        GithubCodeSearcher searcher = searcherReturning("hit\n");
        RepositorySearchService service = new RepositorySearchService(searcher, 1000, 2);

        service.search(REPO_A, "q1");
        service.search(REPO_A, "q2");
        service.search(REPO_A, "q3");           // evicts q1
        String again = service.search(REPO_A, "q1");

        assertEquals("hit", again.trim(), "q1 was evicted, so it is fetched again, not served cached");
        verify(searcher, times(4)).searchCode(any(), anyString());
    }

    @Test
    void cached_hit_does_not_consume_budget() {
        GithubCodeSearcher searcher = searcherReturning("hit\n");
        RepositorySearchService service = new RepositorySearchService(searcher, 1, 256);

        assertTrue(service.search(REPO_A, "q").startsWith("hit"));
        assertTrue(service.search(REPO_A, "q").startsWith("(cached)"));

        verify(searcher, times(1)).searchCode(eq(REPO_A), eq("q"));
    }

    @Test
    void braces_never_reach_the_transport() {
        GithubCodeSearcher searcher = mock(GithubCodeSearcher.class);
        RepositorySearchService service = new RepositorySearchService(searcher, 25, 256);

        String result = service.search(REPO_A, "Foo{bar}");

        assertTrue(result.startsWith("Error:"), result);
        verify(searcher, never()).searchCode(any(), anyString());
    }
}
