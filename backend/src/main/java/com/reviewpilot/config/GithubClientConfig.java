package com.reviewpilot.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
@EnableConfigurationProperties(GithubProperties.class)
public class GithubClientConfig {

    @Bean
    public WebClient githubWebClient(GithubProperties props) {
        WebClient.Builder b = WebClient.builder()
                .baseUrl(props.apiBase())
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .defaultHeader(HttpHeaders.USER_AGENT, "ReviewPilot/0.1")
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        if (!props.token().isBlank()) {
            b.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.token());
        }
        return b.build();
    }
}
