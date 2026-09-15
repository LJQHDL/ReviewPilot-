package com.reviewpilot.service.ai;

/**
 * 端点来源接缝：向评审流程提供「当前应使用哪个 {@link ModelEndpoint}」。
 *
 * <p>这是多厂商可配置化的唯一「来源」调用点。本次由 {@link ConfigModelEndpointResolver}
 * 从服务端配置解析生效端点；将来支持「登录用户自带密钥 / 运行时按请求注入」时，只需替换本接口
 * 的实现（从请求上下文取凭证），评审流程与各 provider 无需改动。
 */
public interface ModelEndpointResolver {

    /** 返回当前生效的端点描述。 @throws RuntimeException 配置无法唯一确定端点时 */
    ModelEndpoint active();
}
