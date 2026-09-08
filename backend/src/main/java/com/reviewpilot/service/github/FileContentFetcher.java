package com.reviewpilot.service.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.reviewpilot.model.PrUrl;
import com.reviewpilot.config.GithubProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** GitHub Contents/PR 元数据适配器：负责抓取并解码源文件内容，不涉及 Prompt 预算策略。 */
@Component
public class FileContentFetcher {

    private static final Logger log = LoggerFactory.getLogger(FileContentFetcher.class);

    private final WebClient github;
    private final GithubProperties props;

    public FileContentFetcher(@Qualifier("githubWebClient") WebClient githubWebClient,
                              GithubProperties props) {
        this.github = githubWebClient;
        this.props = props;
    }

    /** 读取 PR 元数据取 head 分支 ref（Contents API 需要按 ref 定位版本）；失败返回 null。 */
    public String fetchHeadRef(PrUrl pr) {
        try {
            PrMetadata meta = github.get()
                    .uri(GithubApiPaths.pullRequest(pr))
                    .retrieve()
                    .bodyToMono(PrMetadata.class)
                    .block(props.timeout());
            if (meta != null && meta.head() != null && meta.head().ref() != null) {
                return meta.head().ref();
            }
        } catch (RuntimeException e) {
            log.warn("Failed to fetch PR metadata for {}/{}: {}", pr.owner(), pr.repo(), e.toString());
        }
        return null;
    }

    /**
     * ToolRegistry 的公开入口——抓取单个文件内容；文件不存在或不可访问时返回 null。
     */
    public String fetchContent(PrUrl pr, String filePath) {
        String ref = fetchHeadRef(pr);
        if (ref == null || ref.isBlank()) return null;
        try {
            return fetchAtRef(pr, filePath, ref);
        } catch (RuntimeException e) {
            log.debug("Failed to fetch {}: {}", filePath, e.toString());
            return null;
        }
    }

    /**
     * 路径来自模型，因此逐段（segment）拼接：这样既对分隔符做编码（杜绝 {@code ..} 穿越），
     * 也阻止路径里夹带的 {@code ?} 顶掉本方法要设置的 {@code ref} 查询参数。
     */
    public String fetchAtRef(PrUrl pr, String filePath, String ref) {
        String[] segments = filePath.split("/");
        try {
            FileContent fc = github.get()
                    .uri(b -> {
                        var builder = b.pathSegment("repos", pr.owner(), pr.repo(), "contents");
                        for (String segment : segments) {
                            if (!segment.isEmpty()) {
                                builder.pathSegment(segment);
                            }
                        }
                        return builder.queryParam("ref", ref).build();
                    })
                    .retrieve()
                    .bodyToMono(FileContent.class)
                    .block(props.timeout());
            if (fc == null || fc.content() == null) return null;
            // Contents API 返回带换行的 Base64，先去空白再解码为 UTF-8 文本
            byte[] decoded = Base64.getDecoder().decode(
                    fc.content().replaceAll("\\s", ""));
            return new String(decoded, StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            log.debug("Failed to fetch file {}: {}", filePath, e.toString());
            return null;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PrMetadata(Head head) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Head(String ref) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record FileContent(String content, String encoding) {}
}
