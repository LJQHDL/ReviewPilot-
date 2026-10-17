package com.reviewpilot.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** 生产配置失败即停止启动，防止共享 GitHub 凭据成为无限制的仓库读取代理。 */
@Configuration(proxyBeanMethods = false)
@Profile({"prod", "production"})
public class ProductionSecurityConfig {
    public ProductionSecurityConfig(
            @Value("${reviewpilot.security.enabled:false}") boolean enabled,
            GithubProperties github) {
        if (!enabled) {
            throw new IllegalArgumentException("Production requires reviewpilot.security.enabled=true");
        }
        if (github.allowedRepos().isEmpty() || github.allowedRepos().stream().anyMatch(entry ->
                entry == null || !entry.trim().matches("[A-Za-z0-9_-]+/(?:[A-Za-z0-9_.-]+|\\*)"))) {
            throw new IllegalArgumentException("Production requires explicit owner/repo or owner/* repository scopes");
        }
    }
}
