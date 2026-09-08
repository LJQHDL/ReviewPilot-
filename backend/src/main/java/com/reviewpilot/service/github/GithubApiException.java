package com.reviewpilot.service.github;

/** GitHub API 通用调用失败的运行时异常（非鉴权、非 404），由全局异常处理器映射为 HTTP 502。 */
public class GithubApiException extends RuntimeException {
    public GithubApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
