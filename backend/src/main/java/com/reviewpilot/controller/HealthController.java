package com.reviewpilot.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 提供 GET /api/health 存活检查接口，返回服务名与版本信息。 */
@RestController
@RequestMapping("/api")
public class HealthController {

    /** 供前端/运维探活使用的固定状态响应。 */
    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "service", "reviewpilot-backend",
                "version", "0.1.0-SNAPSHOT"
        );
    }
}
