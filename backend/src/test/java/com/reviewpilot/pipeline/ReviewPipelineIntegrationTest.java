package com.reviewpilot.pipeline;

import com.reviewpilot.service.ai.ReviewReplyReader;
import com.reviewpilot.service.ai.RepositorySearchService;
import com.reviewpilot.service.prompt.CriticPromptBuilder;
import com.reviewpilot.service.critic.CriticAgent;
import com.reviewpilot.service.context.RiskFileContextLoader;
import com.reviewpilot.service.github.GithubCodeSearcher;
import com.reviewpilot.service.risk.RiskMerger;

import com.reviewpilot.model.ReviewResult;
import com.reviewpilot.service.ai.AgentResponse;
import com.reviewpilot.service.ai.Message;
import com.reviewpilot.service.ai.ModelProvider;
import com.reviewpilot.service.ai.ReviewAgent;
import com.reviewpilot.service.ai.Tool;
import com.reviewpilot.service.ai.ToolRegistry;
import com.reviewpilot.service.classifier.FileClassifier;
import com.reviewpilot.service.context.ContextLoader;
import com.reviewpilot.service.critic.ReflectionOrchestrator;
import com.reviewpilot.service.diff.DiffParser;
import com.reviewpilot.service.github.FileContentFetcher;
import com.reviewpilot.service.github.GithubPrFetcher;
import com.reviewpilot.service.prompt.PromptBuilder;
import com.reviewpilot.service.risk.RiskDetector;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test: real pipeline collaborators, MockWebServer for GitHub,
 * stub ModelProvider for DeepSeek. Verifies the full review() produces
 * structured output from simulated PR data.
 */
class ReviewPipelineIntegrationTest {

    private MockWebServer githubServer;
    private ReviewPipeline pipeline;

    @BeforeEach
    void setup() {
        githubServer = new MockWebServer();
        String base = githubServer.url("/").toString().replaceAll("/$", "");
        WebClient gh = WebClient.builder().baseUrl(base)
                .defaultHeader("Accept", "application/vnd.github+json").build();

        GithubPrFetcher fetcher = new GithubPrFetcher(gh, new DiffParser(), new com.reviewpilot.config.GithubProperties("https://api.github.com", "", java.util.List.of(), null, 0));
        FileClassifier classifier = new FileClassifier();
        RiskDetector riskDetector = new RiskDetector(List.of(), classifier);
        ContextLoader contextLoader = new ContextLoader();
        FileContentFetcher contentFetcher = new FileContentFetcher(gh, new com.reviewpilot.config.GithubProperties("https://api.github.com", "", java.util.List.of(), null, 0));
        PromptBuilder promptBuilder = new PromptBuilder();
        ModelProvider modelStub = new ModelProvider() {
            public String name() { return "test"; }
            public String complete(String s, String u) {
                return "{\"summary\":\"ok\",\"risks\":[],\"suggestions\":[]}";
            }
            public AgentResponse chat(List<Message> m, List<Tool> t) {
                return new AgentResponse(
                    "{\"summary\":\"ok\",\"risks\":[],\"suggestions\":[]}",
                    List.of(), 0, 0);
            }
        };
        ReflectionOrchestrator orchestrator = new ReflectionOrchestrator(modelStub,
                new CriticAgent(modelStub, new CriticPromptBuilder(), new com.fasterxml.jackson.databind.ObjectMapper()),
                new CriticPromptBuilder(), new ReviewReplyReader(), false);
        ReviewAgent reviewAgent = new ReviewAgent(modelStub, new ToolRegistry(new RepositorySearchService(new GithubCodeSearcher(gh, new com.reviewpilot.config.GithubProperties("https://api.github.com", "", java.util.List.of(), null, 0)), 25, 256), contentFetcher),
                promptBuilder, new ReviewReplyReader(), 8, 64000);

        pipeline = new ReviewPipeline(fetcher, classifier, riskDetector, contextLoader,
                new RiskFileContextLoader(contentFetcher, false), promptBuilder, modelStub, reviewAgent, orchestrator, new RiskMerger(), new com.reviewpilot.service.github.RepoAllowlist(new com.reviewpilot.config.GithubProperties("https://api.github.com", "", java.util.List.of(), null, 0)), 45000, 5);
    }

    @AfterEach
    void tearDown() throws Exception {
        githubServer.shutdown();
    }

    @Test
    void producesStructuredResultForSingleFilePr() {
        String prFilesJson = """
                [{
                  "filename": "src/main/java/Foo.java",
                  "status": "modified",
                  "additions": 1,
                  "deletions": 0,
                  "patch": "@@ -10,3 +10,4 @@\\n public class Foo {\\n+    private int x;\\n }"
                }]""";
        // 1st call: fetchFiles
        githubServer.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(prFilesJson));
        // 2nd call: fetchPrTitle
        githubServer.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"title\":\"add field\",\"head\":{\"ref\":\"feature\"}}"));

        ReviewResult r = pipeline.review("https://github.com/a/b/pull/1");

        assertNotNull(r);
        assertEquals("https://github.com/a/b/pull/1", r.prUrl());
        assertFalse(r.summary().isBlank());
        assertNotNull(r.risks());
        assertNotNull(r.suggestions());
        assertNotNull(r.meta());
        assertEquals("test", r.meta().provider());
        assertEquals(1, r.meta().filesAnalyzed());
        assertTrue(r.meta().elapsedMs() >= 0);
        assertTrue(r.meta().agentRounds() >= 1);
    }
}
