package com.reviewpilot.service.github;

/** GitHub 鉴权失败异常（401/403），携带状态码与上游响应体供日志使用；映射为 HTTP 401。 */
public class GithubAuthException extends RuntimeException {
    private final int status;
    private final String upstreamBody;

    public GithubAuthException(int status, String body, Throwable cause) {
        // 刻意不把响应体放进 message：message 会回传给客户端，
        // 而响应体包含令牌持有者身份及其限流状态
        super("GitHub auth failed (" + status + ")", cause);
        this.status = status;
        this.upstreamBody = body;
    }

    public int status() {
        return status;
    }

    public String upstreamBody() {
        return upstreamBody;
    }
}
