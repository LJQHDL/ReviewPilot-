package com.reviewpilot.controller;

import com.reviewpilot.service.diff.FileChange;
import com.reviewpilot.service.github.FetchedFiles;
import com.reviewpilot.service.github.RepoNotAllowedException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Two things this locks, both of which were broken without any test noticing:
 *
 * <p>1) Requests that never reach a handler method must keep their framework
 * status. A catch-all {@code @ExceptionHandler(Exception.class)} would turn all
 * of them into 500s and log a stack trace per client mistake.
 *
 * <p>2) {@code /api/pr/files} exposes a view, not the internal {@code FileChange}
 * spine object its stages consume.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ApiErrorContractTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private com.reviewpilot.pipeline.PrFilesQuery filesQuery;

    @Test
    void unknown_path_is_a_404_not_a_500() throws Exception {
        mvc.perform(get("/api/definitely-not-a-route"))
                .andExpect(status().isNotFound());
    }

    @Test
    void wrong_http_method_is_a_405_not_a_500() throws Exception {
        mvc.perform(get("/api/review"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void missing_required_param_is_a_400_not_a_500() throws Exception {
        mvc.perform(get("/api/pr/files"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void repository_outside_the_allowlist_is_a_403_with_the_shared_envelope() throws Exception {
        when(filesQuery.files(any())).thenThrow(new RepoNotAllowedException("other/repo"));

        mvc.perform(get("/api/pr/files").param("prUrl", "https://github.com/other/repo/pull/1"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Repository other/repo is not allowed by this service"));
    }

    @Test
    void diff_endpoint_returns_the_view_and_omits_patch_by_default() throws Exception {
        FileChange fc = new FileChange("src/Foo.java", "modified", 2, 1, false,
                "@@ -1 +1 @@ raw patch", List.of());
        when(filesQuery.files(any())).thenReturn(new FetchedFiles(List.of(fc), true));

        mvc.perform(get("/api/pr/files").param("prUrl", "https://github.com/o/r/pull/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.truncated").value(true))
                .andExpect(jsonPath("$.files[0].filename").value("src/Foo.java"))
                .andExpect(jsonPath("$.files[0].hunkCount").value(0))
                // the internal parsed representation must not cross the boundary
                .andExpect(jsonPath("$.files[0].hunks").doesNotExist())
                .andExpect(jsonPath("$.files[0].patch").doesNotExist());
    }

    @Test
    void patch_is_only_returned_when_explicitly_asked_for() throws Exception {
        FileChange fc = new FileChange("src/Foo.java", "modified", 2, 1, false,
                "@@ -1 +1 @@ raw patch", List.of());
        when(filesQuery.files(any())).thenReturn(FetchedFiles.complete(List.of(fc)));

        mvc.perform(get("/api/pr/files")
                        .param("prUrl", "https://github.com/o/r/pull/1")
                        .param("include", "patch"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.files[0].patch").value("@@ -1 +1 @@ raw patch"));
    }
}
