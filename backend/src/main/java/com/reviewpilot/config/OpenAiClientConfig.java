package com.reviewpilot.config;

import com.reviewpilot.service.ai.ModelProvider;
import com.reviewpilot.service.ai.OpenAiCompatibleProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 注册多厂商 AI 配置（{@link AiProperties}），并提供一个<em>通用</em>的 OpenAI 兼容 WebClient.Builder。
 *
 * <p>该 Builder 刻意不设 baseUrl、不设鉴权头——具体地址、Bearer、超时由
 * {@code OpenAiCompatibleProvider} 在每次调用时按 {@code ModelEndpoint} 现拼（clone 后叠加）。
 * 因此一个 Builder 服务所有厂商，换厂商无需新 Bean。
 */
@Configuration
@EnableConfigurationProperties(AiProperties.class)
public class OpenAiClientConfig {

    // 大 PR 的 JSON 输出轻易超过 codec 默认 256KB 上限，与 GitHub 客户端同样调大，
    // 否则 DataBufferLimitException 会表现为莫名的失败。
    private static final int MAX_IN_MEMORY_SIZE = 4 * 1024 * 1024;

    /** 共享模板 Builder：provider 每次 clone 一份再叠加 baseUrl/鉴权/连接器，互不污染。 */
    @Bean
    public WebClient.Builder openAiWebClientBuilder() {
        return WebClient.builder()
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.USER_AGENT, "ReviewPilot/0.1")
                .codecs(c -> c.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY_SIZE));
    }

    /** 全应用唯一的 ModelProvider 实现；协议为 OpenAI 兼容，端点由每次调用的 ModelEndpoint 决定。 */
    @Bean
    public ModelProvider modelProvider() {
        // 直接调用本类的 @Bean 方法（经 CGLIB 代理返回同一模板 Builder），
        // 避免按类型注入 WebClient.Builder 与 Spring 自动配置的 Builder 产生歧义。
        return new OpenAiCompatibleProvider(openAiWebClientBuilder());
    }
}
