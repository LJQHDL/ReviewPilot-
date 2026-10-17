package com.reviewpilot.controller;

import com.reviewpilot.model.ReviewResult;
import com.reviewpilot.pipeline.PrFilesQuery;
import com.reviewpilot.pipeline.ReviewPipeline;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "reviewpilot.security.enabled=true",
        "reviewpilot.security.access-token=0123456789abcdef0123456789abcdef",
        "reviewpilot.security.reviews-per-minute=1"
})
@AutoConfigureMockMvc
class ApiSecurityTest {
    private static final String TOKEN = "Bearer 0123456789abcdef0123456789abcdef";
    @Autowired MockMvc mvc;
    @MockBean ReviewPipeline pipeline;
    @MockBean PrFilesQuery filesQuery;

    @Test
    void rejectsMissingAndIncorrectTokensBeforeInvokingServices() throws Exception {
        mvc.perform(post("/api/review").contentType("application/json")
                        .content("{\"prUrl\":\"https://github.com/o/r/pull/1\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.error").value("Authentication required"));
        mvc.perform(get("/api/pr/files").param("prUrl", "https://github.com/o/r/pull/1")
                        .header("Authorization", "Bearer incorrect"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(pipeline, filesQuery);
    }

    @Test
    void corsErrorsRemainReadableAndFileQueryAcceptsValidToken() throws Exception {
        mvc.perform(get("/api/pr/files").header("Origin", "http://localhost:5173"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
        when(filesQuery.files(anyString())).thenReturn(com.reviewpilot.service.github.FetchedFiles.complete(List.of()));
        mvc.perform(get("/api/pr/files").header("Authorization", TOKEN).param("prUrl", "url"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.files").isArray());
        verify(filesQuery).files("url");
        verifyNoInteractions(pipeline);
    }

    @Test
    void permitsHealthAndCorsPreflightWithoutCredentials() throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isOk());
        mvc.perform(options("/api/review").header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "authorization,content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
        verifyNoInteractions(pipeline, filesQuery);
    }

    @Test
    void authorizesOneReviewThenRejectsExcessBeforePipeline() throws Exception {
        when(pipeline.review(anyString())).thenReturn(
                new ReviewResult("url", "reviewed", List.of(), List.of(), null));
        mvc.perform(post("/api/review").header("Authorization", TOKEN)
                        .contentType("application/json").content("{\"prUrl\":\"url\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.summary").value("reviewed"));
        mvc.perform(post("/api/review").header("Authorization", TOKEN)
                        .contentType("application/json").content("{\"prUrl\":\"url\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.error").exists());
        verify(pipeline, times(1)).review("url");
    }
}
