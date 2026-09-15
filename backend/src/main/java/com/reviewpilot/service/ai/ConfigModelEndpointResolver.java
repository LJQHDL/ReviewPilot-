package com.reviewpilot.service.ai;

import com.reviewpilot.config.AiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@link ModelEndpointResolver} 的本次实现：从服务端配置解析生效端点。
 *
 * <p>构造时立即调用 {@link AiProperties#resolveActive()} 并缓存结果——这既是性能考虑
 * （端点不会在运行期变化），更是把配置校验（F3/F6）绑到 bean 初始化阶段：若端点无法唯一
 * 确定或字段缺失，异常会在应用启动时抛出使启动失败，而非拖到第一次评审请求。
 */
@Component
public class ConfigModelEndpointResolver implements ModelEndpointResolver {

    private static final Logger log = LoggerFactory.getLogger(ConfigModelEndpointResolver.class);

    private final ModelEndpoint active;

    public ConfigModelEndpointResolver(AiProperties props) {
        this.active = props.resolveActive();   // 启动期快速失败；成功后缓存
        log.info("Active AI endpoint: name={}, model={}, apiBase={}, key={}",
                active.name(), active.model(), active.baseUrl(), active.redactedKey());
    }

    @Override
    public ModelEndpoint active() {
        return active;
    }
}
