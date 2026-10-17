package com.reviewpilot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 绑定 application.yml 中 {@code reviewpilot.ai.deepseek.*} 配置项的不可变记录，并为各字段提供默认值。
 * <p>
 * API Key 必须来自 {@code DEEPSEEK_API_KEY} 环境变量（yml 中写 {@code ${DEEPSEEK_API_KEY:}}），
 * 切勿把密钥值提交到 yml、env 文件或测试代码中。
 *
 * @param apiBase     API 基地址，可覆盖为自建网关，默认 https://api.deepseek.com
 * @param apiKey      Bearer Token，为空表示服务未启用，/api/review 会返回明确错误而非崩溃
 * @param model       DeepSeek 模型 ID（如 deepseek-chat、deepseek-reasoner）
 * @param timeout     HTTP 读取超时，默认 60 秒（大 PR 的代码分析较慢）
 * @param maxTokens   输出 Token 上限，4096 足以覆盖大多数 PR 的 JSON 结果
 * @param temperature 采样温度，0.2 可让 JSON 输出保持稳定
 */
@ConfigurationProperties("reviewpilot.ai.deepseek")
public record DeepSeekProperties(
        String apiBase,
        String apiKey,
        String model,
        Duration timeout,
        Integer maxTokens,
        Double temperature,
        Integer maxRetries
) {

    /** 紧凑构造器：为缺失或非法的配置值填充默认值。 */
    public DeepSeekProperties {
        if (apiBase == null || apiBase.isBlank()) {
            apiBase = "https://api.deepseek.com";
        }
        if (apiKey == null) {
            apiKey = "";
        }
        if (model == null || model.isBlank()) {
            model = "deepseek-chat";
        }
        if (timeout == null) {
            timeout = Duration.ofSeconds(60);
        }
        if (maxTokens == null || maxTokens <= 0) {
            maxTokens = 4096;
        }
        if (temperature == null || temperature < 0) {
            temperature = 0.2;
        }
        if (maxRetries == null || maxRetries < 0) {
            maxRetries = 1;
        }
    }

    /** API Key 是否已配置（未配置时评审接口应直接报错）。 */
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
