package com.reviewpilot.service.context;

import com.reviewpilot.model.PrUrl;
import com.reviewpilot.service.github.FileContentFetcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 为规则命中风险的文件做可选的批量内容增强，并执行 Prompt 体积（截断）策略。 */
@Component
public class RiskFileContextLoader {
    private static final Logger log = LoggerFactory.getLogger(RiskFileContextLoader.class);
    private static final int MAX_CONTENT_CHARS = 8_000;   // 单文件注入 Prompt 前的最大字符数
    private final FileContentFetcher fetcher;
    private final boolean enabled;

    public RiskFileContextLoader(FileContentFetcher fetcher,
            @Value("${reviewpilot.agent.content-fetcher.enabled:true}") boolean enabled) {
        this.fetcher = fetcher;
        this.enabled = enabled;
    }

    /** 批量抓取风险文件的完整内容：先解析 PR head ref，再逐文件抓取并截断；单文件失败不影响其余。 */
    public Map<String, String> fetchForRiskyFiles(PrUrl pr, List<String> riskyFilePaths) {
        if (!enabled) return Map.of();
        Map<String, String> results = new HashMap<>();
        // 没有 head ref 就无法定位文件版本，整批放弃而不是猜测
        String ref = fetcher.fetchHeadRef(pr);
        if (ref == null || ref.isBlank()) {
            log.warn("Could not determine PR head ref; skipping full-file fetching");
            return results;
        }
        for (String path : riskyFilePaths) {
            try {
                String content = fetcher.fetchAtRef(pr, path, ref);
                if (content != null) {
                    results.put(path, truncate(content));
                }
            } catch (RuntimeException e) {
                // 增强上下文是可选能力，抓取失败静默降级为"无全文"
                log.debug("Failed to fetch content for {}: {}", path, e.toString());
            }
        }
        return results;
    }

    /** 超长文件截断到上限并标注截掉的字符数，防止撑爆 Prompt 预算。 */
    private static String truncate(String s) {
        if (s.length() <= MAX_CONTENT_CHARS) return s;
        return s.substring(0, MAX_CONTENT_CHARS)
                + "\n\n[...truncated " + (s.length() - MAX_CONTENT_CHARS) + " chars]";
    }

}
