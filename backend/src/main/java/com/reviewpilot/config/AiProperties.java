package com.reviewpilot.config;

import com.reviewpilot.service.ai.ModelEndpoint;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Map;

/**
 * 绑定 {@code reviewpilot.ai.*} 的配置：多套具名端点 + 一个生效开关。
 *
 * <p>结构（yaml）：
 * <pre>
 *   reviewpilot:
 *     ai:
 *       active: deepseek            # 可省略——只声明一个端点时自动生效
 *       endpoints:
 *         deepseek:
 *           api-base: https://api.deepseek.com
 *           api-key: ${DEEPSEEK_API_KEY:}   # 密钥只从环境变量注入，勿写明文
 *           model: deepseek-chat
 *           timeout: 60s            # 以下均可省略，走默认值
 *           max-tokens: 4096
 *           temperature: 0.2
 *           max-retries: 1
 *         kimi: { ... }             # 再加一家，改 active 即切换
 * </pre>
 *
 * <p>生效端点的确定规则（F3）与快速失败校验（F6）在 {@link #resolveActive()} 中执行。
 * 个人项目阶段不做旧 {@code reviewpilot.ai.deepseek.*} 形态的向后兼容。
 */
@ConfigurationProperties("reviewpilot.ai")
public record AiProperties(
        String active,
        Map<String, EndpointSpec> endpoints
) {

    /** 单套端点的原始配置；字段可空，默认值在 {@link #toEndpoint(String)} 中填充。 */
    public record EndpointSpec(
            String apiBase,
            String apiKey,
            String model,
            Duration timeout,
            Integer maxTokens,
            Double temperature,
            Integer maxRetries
    ) {
        /** 转成运行时值对象；api-base 缺失在此快速失败并定位到端点名。 */
        ModelEndpoint toEndpoint(String name) {
            if (apiBase == null || apiBase.isBlank()) {
                throw new IllegalStateException("reviewpilot.ai.endpoints." + name
                        + ".api-base is required but missing");
            }
            String effectiveModel = (model != null && !model.isBlank())
                    ? model : "default-" + name;
            return new ModelEndpoint(name, apiBase, apiKey, effectiveModel,
                    timeout, maxTokens == null ? 0 : maxTokens,
                    temperature == null ? -1 : temperature,
                    maxRetries == null ? -1 : maxRetries);
        }
    }

    /**
     * 按 F3 规则确定生效端点。
     *
     * @throws IllegalStateException 无任何端点（服务无法工作）、active 指向未声明的端点、
     *                               或多端点未声明 active（宁可启动失败也不猜默认）
     */
    public ModelEndpoint resolveActive() {
        if (endpoints == null || endpoints.isEmpty()) {
            throw new IllegalStateException(
                    "no AI endpoint configured — define reviewpilot.ai.endpoints.<name>.*");
        }
        if (active != null && !active.isBlank()) {
            EndpointSpec spec = endpoints.get(active);
            if (spec == null) {
                throw new IllegalStateException("reviewpilot.ai.active='" + active
                        + "' names an undeclared endpoint; declared endpoints are: "
                        + endpoints.keySet());
            }
            return spec.toEndpoint(active);
        }
        if (endpoints.size() > 1) {
            throw new IllegalStateException("multiple AI endpoints declared but "
                    + "reviewpilot.ai.active is not set — declared endpoints: "
                    + endpoints.keySet());
        }
        Map.Entry<String, EndpointSpec> only = endpoints.entrySet().iterator().next();
        return only.getValue().toEndpoint(only.getKey());
    }
}
