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

@Configuration
@EnableConfigurationProperties(GithubProperties.class)
public class GithubClientConfig {

    private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
    // A 100-file page carries a full patch per file and routinely outruns the
    // 256KB codec default; the DeepSeek client raises the same limit for the same
    // reason, and a DataBufferLimitException there looks like a mystery 500.
    private static final int MAX_IN_MEMORY_SIZE = 8 * 1024 * 1024;

    @Bean
    public WebClient githubWebClient(GithubProperties props) {
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

        if (!props.token().isBlank()) {
            b.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.token());
        }
        return b.build();
    }
}
