package com.reviewpilot.config;

import io.netty.channel.ChannelOption;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

/** 构建调用 GitHub REST API 的 WebClient Bean，统一配置超时、API 版本头和鉴权信息。 */
@Configuration
@EnableConfigurationProperties(GithubProperties.class)
public class GithubClientConfig {

    private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
    // 单页 100 个文件时每页都带完整 patch，轻易超过 256KB 的 codec 默认上限；
    // 与 DeepSeek 客户端同样调大，否则 DataBufferLimitException 会表现为莫名的 500。
    private static final int MAX_IN_MEMORY_SIZE = 8 * 1024 * 1024;

    @Bean
    public WebClient githubWebClient(GithubProperties props) {
        // 连接超时 10 秒；读取超时逐调用生效，防止卡死的连接占住 Servlet 线程
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MILLIS)
                .responseTimeout(props.timeout());

        WebClient.Builder b = WebClient.builder()
                .baseUrl(props.apiBase())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .defaultHeader(HttpHeaders.USER_AGENT, "ReviewPilot/0.1")
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);

        b.codecs(c -> c.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY_SIZE));

        // 仅在配置了 Token 时附加 Bearer 鉴权头，否则匿名访问（60 次/小时限流）
        if (!props.token().isBlank()) {
            b.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.token());
        }
        return b.build();
    }
}
