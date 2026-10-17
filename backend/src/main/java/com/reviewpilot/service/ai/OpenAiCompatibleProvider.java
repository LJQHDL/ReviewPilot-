package com.reviewpilot.service.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.netty.channel.ChannelOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.netty.http.client.HttpClient;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link ModelProvider} 的 OpenAI 兼容实现，调用 {@code POST /v1/chat/completions} 端点。
 *
 * <p>覆盖所有遵循 OpenAI 对话补全约定的厂商（DeepSeek、OpenAI、Kimi、Qwen、GLM、Groq、
 * Ollama、vLLM、SiliconFlow 等）——换厂商只需改配置里的 {@link ModelEndpoint}，本类不关心具体是谁。
 *
 * <p>类本身<em>无端点状态</em>：地址、密钥、模型、超时、重试全部来自每次调用传入的
 * {@link ModelEndpoint}。WebClient 按端点惰性构建并缓存（端点在应用生命周期内不变）。
 * API Key 只从环境变量注入；日志中只允许出现 {@link ModelEndpoint#redactedKey()}。
 */
public class OpenAiCompatibleProvider implements ModelProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleProvider.class);
    private static final int CONNECT_TIMEOUT_MILLIS = 10_000;

    private final WebClient.Builder clientBuilder;
    /** 端点为应用级常量，缓存其 WebClient；key 用 name，冲突时以 baseUrl 兜底区分。 */
    private final Map<String, WebClient> clients = new ConcurrentHashMap<>();

    public OpenAiCompatibleProvider(WebClient.Builder openAiWebClientBuilder) {
        this.clientBuilder = openAiWebClientBuilder;
    }

    @Override
    public String name() {
        return "openai-compatible";
    }

    /** 多轮聊天（V3 ReAct 用）：携带完整消息历史与可选工具定义，返回文本或工具调用。 */
    @Override
    public AgentResponse chat(ModelEndpoint ep, List<Message> messages, List<Tool> tools) {
        requireKey(ep);

        // 组装 OpenAI 兼容请求体；messages/tools 经 OpenAiMessageMapper 转成线格式
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("model", ep.model());
        body.put("temperature", ep.temperature());
        body.put("max_tokens", ep.maxTokens());
        body.put("stream", false);
        body.put("messages", messages.stream().map(OpenAiMessageMapper::message).toList());
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools.stream().map(OpenAiMessageMapper::tool).toList());
        }

        OaiCompletion response = callWithRetry(ep, body, "chat");
        if (response == null) {
            throw new AiProviderException("OpenAI-compatible chat call failed after retries");
        }
        if (response.choices() == null || response.choices().isEmpty()) {
            throw new AiProviderException(ep.name() + " returned no choices");
        }
        OaiChoice first = response.choices().get(0);
        if (first.message() == null) {
            throw new AiProviderException(ep.name() + " response missing message");
        }

        // 把线格式响应翻译回内部模型：文本 + 工具调用 + 真实 token 用量
        String content = first.message().content();
        List<ToolCall> toolCalls = first.message().toolCalls() != null
                ? first.message().toolCalls().stream().map(OaiToolCall::toToolCall).toList()
                : List.of();
        int promptTokens = response.usage() != null ? response.usage().promptTokens() : 0;
        int completionTokens = response.usage() != null ? response.usage().completionTokens() : 0;

        return new AgentResponse(
                content != null ? content : "",
                toolCalls,
                promptTokens, completionTokens);
    }

    /** 单发式补全（V1/V2）：system+user 两条消息，返回纯文本内容。 */
    @Override
    public String complete(ModelEndpoint ep, String systemPrompt, String userPrompt) {
        requireKey(ep);

        Map<String, Object> body = Map.of(
                "model", ep.model(),
                "temperature", ep.temperature(),
                "max_tokens", ep.maxTokens(),
                "stream", false,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)
                )
        );

        OaiCompletion response = callWithRetry(ep, body, "complete");
        if (response == null) {
            throw new AiProviderException("OpenAI-compatible complete call failed after retries");
        }
        if (response.choices() == null || response.choices().isEmpty()) {
            throw new AiProviderException(ep.name() + " returned no choices");
        }
        OaiChoice first = response.choices().get(0);
        if (first.message() == null || first.message().content() == null) {
            throw new AiProviderException(ep.name() + " response missing message content");
        }
        return first.message().content();
    }

    /** 密钥未配置时抛鉴权异常，让评审接口返回 401 而非发起一次注定失败的调用。 */
    private void requireKey(ModelEndpoint ep) {
        if (!ep.isConfigured()) {
            throw new AiAuthenticationException(
                    "Endpoint '" + ep.name() + "' API key is not set. Export its key env var before calling /api/review.");
        }
    }

    /** 按端点惰性构建并缓存 WebClient：baseUrl + Bearer 头 + 读超时都取自该端点。 */
    private WebClient clientFor(ModelEndpoint ep) {
        return clients.computeIfAbsent(ep.name() + "@" + ep.baseUrl() + "#" + ep.timeout().toMillis(), k -> {
            HttpClient httpClient = HttpClient.create()
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MILLIS)
                    .responseTimeout(ep.timeout());
            WebClient.Builder b = clientBuilder.clone()
                    .baseUrl(ep.baseUrl())
                    .clientConnector(new ReactorClientHttpConnector(httpClient));
            if (ep.isConfigured()) {
                b.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + ep.apiKey());
            }
            return b.build();
        });
    }

    /**
     * 带重试的调用：仅对瞬时网络错误（连接重置、超时）重试最多 {@code ep.maxRetries()} 次；
     * 带 HTTP 状态码的 4xx/5xx 属于确定性错误，不重试、立即抛出。
     */
    private OaiCompletion callWithRetry(ModelEndpoint ep, Map<String, Object> body, String caller) {
        WebClient client = clientFor(ep);
        int maxRetries = ep.maxRetries();
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return client.post()
                        .uri("/v1/chat/completions")
                        .bodyValue(body)
                        .retrieve()
                        .bodyToMono(OaiCompletion.class)
                        .block();
            } catch (WebClientResponseException e) {
                // 带状态码的 HTTP 错误（4xx/5xx）非瞬时故障，直接失败；
                // 上游响应体可能包含身份、代码或凭据，只记状态码，绝不落日志或回传。
                log.warn("{} ({}) call failed: status={}", ep.name(), caller, e.getStatusCode().value());
                throw new AiProviderException(
                        ep.name() + " API call failed with status " + e.getStatusCode().value(), e);
            } catch (RuntimeException e) {
                // 按错误文本猜测是否为瞬时故障；退避时间为 attempt+1 秒
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                boolean isTransient = msg.contains("reset") || msg.contains("timeout")
                        || msg.contains("timed out") || msg.contains("refused");
                if (attempt < maxRetries && isTransient) {
                    long delay = (attempt + 1) * 1000L;
                    log.warn("{} ({}) transient error (attempt {}/{}): type={} — retrying in {}s",
                            ep.name(), caller, attempt + 1, maxRetries + 1,
                            e.getClass().getSimpleName(), delay / 1000);
                    try { Thread.sleep(delay); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); break; }
                    continue;
                }
                if (attempt >= maxRetries) {
                    log.error("{} ({}) failed after {} retries: type={}",
                            ep.name(), caller, maxRetries, e.getClass().getSimpleName());
                }
                throw new AiProviderException(ep.name() + " API call failed", e);
            }
        }
        return null;
    }

    // --- 响应 DTO（OpenAI 兼容 Schema 的最小子集） -----------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OaiCompletion(String id, String model, List<OaiChoice> choices,
                                OaiUsage usage) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OaiChoice(int index, OaiMessage message, String finish_reason) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OaiMessage(String role, String content,
                             @com.fasterxml.jackson.annotation.JsonProperty("tool_calls")
                             List<OaiToolCall> toolCalls) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OaiToolCall(String id, String type, OaiFunction function) {
        /** 转为内部 ToolCall：arguments 是 JSON 字符串，需解析为 Map。 */
        public ToolCall toToolCall() {
            return new ToolCall(id, function.name(),
                    parseArguments(function.arguments()));
        }
        /** 解析失败时返回空 Map——让上层把"无参数"当作普通错误处理而非崩溃。 */
        private static Map<String, Object> parseArguments(String json) {
            if (json == null || json.isBlank()) return Map.of();
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue(json, Map.class);
                return map;
            } catch (Exception e) {
                return Map.of();
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OaiFunction(String name, String arguments) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OaiUsage(@com.fasterxml.jackson.annotation.JsonProperty("prompt_tokens") int promptTokens,
                           @com.fasterxml.jackson.annotation.JsonProperty("completion_tokens") int completionTokens) {}
}
