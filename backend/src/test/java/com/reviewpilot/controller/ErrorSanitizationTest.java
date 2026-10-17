package com.reviewpilot.controller;

import com.reviewpilot.model.PrUrl;
import com.reviewpilot.service.ai.*;
import com.reviewpilot.service.github.FileContentFetcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.system.CapturedOutput;
import com.reviewpilot.service.github.GithubAuthException;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class ErrorSanitizationTest {
    @Test
    void upstreamExceptionsDoNotExposeSecretsToClientsOrModel() {
        var handler = new ApiExceptionHandler();
        var response = handler.handleAi(new AiProviderException("secret-marker private upstream URL"));
        assertEquals(502, response.getStatusCode().value());
        assertFalse(response.getBody().error().contains("secret-marker"));
    }

    @Test
    void toolFailureDoesNotExposeExceptionOrUntrustedToolName(CapturedOutput output) {
        var fetcher = mock(FileContentFetcher.class);
        when(fetcher.fetchContent(any(), anyString())).thenThrow(new RuntimeException("secret-marker"));
        var registry = new ToolRegistry(mock(RepositorySearchService.class), fetcher);
        var result = registry.execute(new ToolCall("id", "fetch_file_content", Map.of("path", "A.java")),
                new PrUrl("o", "r", 1));
        assertFalse(result.contains("secret-marker"));
        assertFalse(output.getAll().contains("secret-marker"));
    }

    @Test
    void githubResponseBodyIsNotLogged(CapturedOutput output) {
        new ApiExceptionHandler().handleGithubAuth(new GithubAuthException(403, "secret-marker", null));
        assertFalse(output.getAll().contains("secret-marker"));
    }
}
