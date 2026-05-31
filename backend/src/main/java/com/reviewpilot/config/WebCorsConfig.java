package com.reviewpilot.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS for the Vue dev server. In dev the frontend usually goes through the
 * Vite proxy ({@code frontend/vite.config.js}) so this only matters when the
 * SPA is served from a different origin (production build, demo from a static
 * host, or hitting the backend directly).
 *
 * Origins are configurable via {@code reviewpilot.web.cors.allowed-origins};
 * default covers the Vite dev server on localhost.
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
