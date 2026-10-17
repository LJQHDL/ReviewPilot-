package com.reviewpilot.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionSecurityTest {
    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackageClasses = GithubClientConfig.class)
    static class Config {}

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Config.class)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withPropertyValues("spring.profiles.active=prod",
                    "reviewpilot.security.enabled=true",
                    "reviewpilot.security.access-token=0123456789abcdef0123456789abcdef");

    @Test
    void productionRejectsEmptyOrUnboundedRepositoryScope() {
        runner.run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("reviewpilot.github.allowed-repos[0]=*/*")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("reviewpilot.github.allowed-repos[0]=bad-entry")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void productionRejectsDisabledAuthAndMissingOrWeakTokens() {
        var scoped = runner.withPropertyValues("reviewpilot.github.allowed-repos[0]=my-org/*");
        scoped.withPropertyValues("reviewpilot.security.enabled=false")
                .run(context -> assertThat(context).hasFailed());
        scoped.withPropertyValues("reviewpilot.security.access-token=")
                .run(context -> assertThat(context).hasFailed());
        scoped.withPropertyValues("reviewpilot.security.access-token=short")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void permitsExplicitScopeAndSupportsProductionProfileAlias() {
        runner.withPropertyValues("reviewpilot.github.allowed-repos[0]=my-org/*")
                .run(context -> assertThat(context).hasNotFailed());
        runner.withPropertyValues("spring.profiles.active=production")
                .run(context -> assertThat(context).hasFailed());
    }
}
