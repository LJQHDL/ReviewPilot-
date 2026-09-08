package com.reviewpilot.service.ai;

/** 模型鉴权/配置错误（如 Key 缺失或无效），独立于 Provider 的具体报错文案，映射为 HTTP 401。 */
public class AiAuthenticationException extends AiProviderException {
    public AiAuthenticationException(String message) { super(message); }
}
