package com.reviewpilot.controller;

import com.reviewpilot.model.ErrorResponse;
import com.reviewpilot.service.ai.AiProviderException;
import com.reviewpilot.service.github.GithubApiException;
import com.reviewpilot.service.github.GithubAuthException;
import com.reviewpilot.service.github.GithubPrNotFoundException;
import com.reviewpilot.service.github.RepoNotAllowedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;

import org.springframework.web.bind.annotation.RestControllerAdvice;
import com.reviewpilot.service.ai.AiAuthenticationException;

/** 全局异常处理器，按异常类型将各类错误统一映射为带状态码的 JSON 错误响应。 */
@RestControllerAdvice(basePackages = "com.reviewpilot.controller")
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** 入参非法（如 PR URL 格式错误）→ 400。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleBadInput(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(ErrorResponse.of(e.getMessage()));
    }

    /** PR 不存在或不可见 → 404。 */
    @ExceptionHandler(GithubPrNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(GithubPrNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.of(e.getMessage()));
    }

    /** GitHub 鉴权失败 → 401。 */
    @ExceptionHandler(GithubAuthException.class)
    public ResponseEntity<ErrorResponse> handleGithubAuth(GithubAuthException e) {
        // 上游响应体只写日志、绝不回传：它包含令牌持有者及其限流状态，
        // 且把 403 与 404 的差异暴露出去会让本接口变成私有仓库存在性探测工具。
        log.warn("GitHub auth rejected: status={} upstream body={}", e.status(), e.upstreamBody());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ErrorResponse.of(e.getMessage()));
    }

    /** GitHub 其他上游错误 → 502。 */
    @ExceptionHandler(GithubApiException.class)
    public ResponseEntity<ErrorResponse> handleGithubApi(GithubApiException e) {
        // 401/403/404 之外的任意 GitHub 状态码统一转 502，
        // 否则 Spring 默认的 {timestamp,status,error,path} 响应体前端拦截器读不到，
        // 所有上游故障都只会显示成"请求失败"。
        log.warn("GitHub upstream failure: {}", e.toString());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ErrorResponse.of("GitHub API request failed"));
    }

    /** 仓库不在白名单内 → 403。 */
    @ExceptionHandler(RepoNotAllowedException.class)
    public ResponseEntity<ErrorResponse> handleRepoNotAllowed(RepoNotAllowedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ErrorResponse.of(e.getMessage()));
    }

    /** AI 提供方错误 → 鉴权问题 401，其余 502。 */
    @ExceptionHandler(AiProviderException.class)
    public ResponseEntity<ErrorResponse> handleAi(AiProviderException e) {
        HttpStatus status = e instanceof AiAuthenticationException
                ? HttpStatus.UNAUTHORIZED : HttpStatus.BAD_GATEWAY;
        return ResponseEntity.status(status).body(ErrorResponse.of(e.getMessage()));
    }
}
