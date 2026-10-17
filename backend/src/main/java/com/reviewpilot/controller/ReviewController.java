package com.reviewpilot.controller;

import com.reviewpilot.model.ReviewResult;
import com.reviewpilot.pipeline.ReviewPipeline;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 核心评审入口 POST /api/review，接收 PR URL 并委托 ReviewPipeline 执行完整评审。
 *
 * <pre>
 *   POST /api/review
 *   { "prUrl": "https://github.com/owner/repo/pull/12" }
 *   ->
 *   200 { ReviewResult }
 *   400 { error: "<reason>" }     (URL 非法)
 *   404 { error: "..." }          (PR 不存在或私有)
 *   401 { error: "..." }          (GitHub 鉴权/限流，或未设置 DeepSeek Key)
 *   502 { error: "..." }          (DeepSeek API 调用失败)
 * </pre>
 */
@RestController
@RequestMapping("/api")
public class ReviewController {
    private final ReviewPipeline pipeline;

    public ReviewController(ReviewPipeline pipeline) {
        this.pipeline = pipeline;
    }

    /** 请求体：仅承载 URL 字符串，URL 语义校验由应用层用例负责。 */
    public record ReviewRequest(String prUrl) {}

    @PostMapping("/review")
    public ReviewResult review(@RequestBody ReviewRequest request) {
        // 先做空值防御，再交给流水线执行完整评审
        if (request == null || request.prUrl() == null || request.prUrl().isBlank()) {
            throw new IllegalArgumentException("prUrl is required");
        }
        return pipeline.review(request.prUrl());
    }

}
