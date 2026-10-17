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

/** 构建调用 DeepSeek API 的 WebClient Bean，统一配置超时、请求头和鉴权信息。 */
@Configuration
@EnableConfigurationProperties(DeepSeekProperties.class)
public class DeepSeekClientConfig {

    @Bean
    public WebClient deepSeekWebClient(DeepSeekProperties props) {
        // 连接超时固定 10 秒，读取超时取自配置（大 PR 的代码分析耗时较长）
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
                .responseTimeout(props.timeout());

        WebClient.Builder b = WebClient.builder()
                .baseUrl(props.apiBase())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.USER_AGENT, "ReviewPilot/0.1");

        // 放大响应缓冲：大 PR 可能产生超过 256KB 的 JSON 输出
        b.codecs(c -> c.defaultCodecs().maxInMemorySize(4 * 1024 * 1024));

        // 仅在 API Key 已配置时附加 Bearer 鉴权头，未配置时由上层返回明确错误
        if (props.isConfigured()) {
            b.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.apiKey());
        }
        return b.build();
    }
}
