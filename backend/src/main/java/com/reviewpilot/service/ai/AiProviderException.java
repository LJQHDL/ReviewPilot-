package com.reviewpilot.service.ai;

/** AI 提供方调用失败的通用运行时异常基类，由全局异常处理器映射为 HTTP 502。 */
public class AiProviderException extends RuntimeException {
    public AiProviderException(String message) {
        super(message);
    }

    public AiProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
