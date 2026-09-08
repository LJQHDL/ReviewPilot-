package com.reviewpilot.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 为跨域访问的 Vue 前端配置 /api/** 的 CORS 规则。
 * 开发环境通常走 Vite 代理（frontend/vite.config.js），因此仅当 SPA 从其他来源
 * 提供服务（生产构建、静态托管演示、或直连后端）时才需要它。
 * 允许的来源可通过 {@code reviewpilot.web.cors.allowed-origins} 配置，
 * 默认覆盖本机 localhost 上的 Vite 开发服务器。
 */
@Configuration
public class WebCorsConfig implements WebMvcConfigurer {

    private final String[] allowedOrigins;

    public WebCorsConfig(
            @Value("${reviewpilot.web.cors.allowed-origins:http://localhost:5173,http://127.0.0.1:5173}")
            String[] allowedOrigins
    ) {
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void addCorsMappings(@NonNull CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }
}
