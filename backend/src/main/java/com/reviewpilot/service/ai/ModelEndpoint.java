package com.reviewpilot.service.ai;

import java.time.Duration;

/**
 * 一个具名模型端点的完整描述：地址、密钥、模型与调用参数。
 *
 * <p>它是「发起一次模型调用所需的全部端点信息」的值对象，随每次调用经
 * {@link ModelProvider} 的入参传入，而非焊死在 provider 内部——这是
 * 「端点来源将来可从服务端配置切换为运行时注入」的数据载体。
 *
 * @param name        具名端点名（配置里 {@code reviewpilot.ai.endpoints.<name>} 的 name），进入结果 meta.provider
 * @param baseUrl     OpenAI 兼容服务根地址，如 https://api.deepseek.com
 * @param apiKey      Bearer Token；空表示未配置，评审接口据此返回明确鉴权错误而非崩溃
 * @param model       模型标识（如 deepseek-chat、moonshot-v1-8k），进入结果 meta.model
 * @param timeout     单次 HTTP 读超时
 * @param maxTokens   输出 Token 上限
 * @param temperature 采样温度
 * @param maxRetries  瞬时网络错误的重试次数；带状态码的 4xx/5xx 不重试
 */
public record ModelEndpoint(
        String name,
        String baseUrl,
        String apiKey,
        String model,
        Duration timeout,
        int maxTokens,
        double temperature,
        int maxRetries
) {

    /** 紧凑构造器：为缺失或非法的调用参数兜底默认值（name/baseUrl 的必填校验在配置层做）。 */
    public ModelEndpoint {
        if (apiKey == null) {
            apiKey = "";
        }
        if (timeout == null) {
            timeout = Duration.ofSeconds(60);
        }
        if (maxTokens <= 0) {
            maxTokens = 4096;
        }
        if (temperature < 0) {
            temperature = 0.2;
        }
        if (maxRetries < 0) {
            maxRetries = 1;
        }
    }

    /** 密钥是否已配置（未配置时评审接口应直接报鉴权错误）。 */
    public boolean isConfigured() {
        return !apiKey.isBlank();
    }

    /**
     * 返回密钥的脱敏形式（{@code ****后4位}）供日志使用，严禁打印完整 {@link #apiKey()}。
     */
    public String redactedKey() {
        if (apiKey.isBlank()) {
            return "(unset)";
        }
        if (apiKey.length() <= 4) {
            return "****";
        }
        return "****" + apiKey.substring(apiKey.length() - 4);
    }
}
