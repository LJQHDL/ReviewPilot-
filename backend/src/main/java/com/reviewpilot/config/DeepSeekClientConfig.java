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
@EnableConfigurationProperties(DeepSeekProperties.class)
public class DeepSeekClientConfig {

    @Bean
    public WebClient deepSeekWebClient(DeepSeekProperties props) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
                .responseTimeout(props.timeout());

        WebClient.Builder b = WebClient.builder()
                .baseUrl(props.apiBase())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.USER_AGENT, "ReviewPilot/0.1");

        // Bigger response buffer — long PRs can produce >256KB JSON output.
        b.codecs(c -> c.defaultCodecs().maxInMemorySize(4 * 1024 * 1024));

        if (props.isConfigured()) {
            b.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.apiKey());
        }
        return b.build();
    }
}
