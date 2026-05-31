package com.reviewpilot.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reviewpilot.model.ErrorResponse;
import com.reviewpilot.model.PrUrl;
import com.reviewpilot.pipeline.ReviewPipeline;
import com.reviewpilot.service.ai.AiProviderException;
import com.reviewpilot.service.github.GithubAuthException;
import com.reviewpilot.service.github.GithubPrNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Locks the PR#9 unification of error responses: every {@code @ExceptionHandler}
 * in {@link ReviewController} must serialize as {@code {"error": "..."}} —
 * the field name the frontend axios interceptor (PR#7) reads. A future change
 * to {@link ErrorResponse} that renames the field would silently break the
 * SPA's error display; these tests catch that at build time.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReviewControllerErrorTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @MockBean
    private ReviewPipeline pipeline;

    @Test
    void blank_url_returns_400_with_error_field() throws Exception {
        mvc.perform(post("/api/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new ReviewController.ReviewRequest(""))))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").value("prUrl is required"));
    }

    @Test
    void missing_pr_returns_404_with_error_field() throws Exception {
        when(pipeline.review(any())).thenThrow(
                new GithubPrNotFoundException(new PrUrl("octocat", "Hello-World", 999), null));

        mvc.perform(post("/api/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new ReviewController.ReviewRequest("https://github.com/o/r/pull/1"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value(containsString("PR not found")));
    }

    @Test
    void github_auth_returns_401_with_error_field() throws Exception {
        when(pipeline.review(any())).thenThrow(new GithubAuthException(401, "bad creds", null));

        mvc.perform(post("/api/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new ReviewController.ReviewRequest("https://github.com/o/r/pull/1"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value(containsString("GitHub auth failed")));
    }

    @Test
    void missing_deepseek_key_returns_401_with_error_field() throws Exception {
        when(pipeline.review(any())).thenThrow(new AiProviderException("DeepSeek API key is not set"));

        mvc.perform(post("/api/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new ReviewController.ReviewRequest("https://github.com/o/r/pull/1"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("DeepSeek API key is not set"));
    }

    @Test
    void deepseek_upstream_failure_returns_502_with_error_field() throws Exception {
        when(pipeline.review(any())).thenThrow(new AiProviderException("upstream 503"));

        mvc.perform(post("/api/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new ReviewController.ReviewRequest("https://github.com/o/r/pull/1"))))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("upstream 503"));
    }
}
