package com.reviewpilot.service.github;

import com.reviewpilot.config.GithubProperties;
import com.reviewpilot.model.PrUrl;
import com.reviewpilot.service.diff.DiffParser;
import com.reviewpilot.service.diff.FileChange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.ArrayList;
import java.util.List;

/**
 * 抓取 GitHub PR 的变更文件列表，并把每个文件转成下游管线可直接消费的 {@link FileChange}。
 * <p>
 * 逐文件 {@code patch} 经 {@link DiffParser} 解析为 hunks；二进制或无 patch 的文件 hunks 为空。
 * <p>
 * 每次调用都受 {@link GithubProperties#timeout()} 约束——这些调用阻塞 Servlet 线程，
 * 一次无界停顿等于泄漏一个工作线程。
 */
@Service
public class GithubPrFetcher {

    private static final Logger log = LoggerFactory.getLogger(GithubPrFetcher.class);

    private static final int FILES_PER_PAGE = 100;

    private static final ParameterizedTypeReference<List<GithubPrFile>> PR_FILES =
            new ParameterizedTypeReference<>() {};

    private final WebClient github;
    private final DiffParser diffParser;
    private final GithubProperties props;

    public GithubPrFetcher(WebClient githubWebClient, DiffParser diffParser, GithubProperties props) {
        this.github = githubWebClient;
        this.diffParser = diffParser;
        this.props = props;
    }

    /**
     * 最多翻 {@code max-file-pages} 页的分页文件端点，并报告 PR 是否被完整覆盖。
     * 早期单次不分页的请求把所有 PR 截死在 100 个文件，结果却声称描述了全部内容。
     */
    public FetchedFiles fetchFiles(PrUrl pr) {
        List<FileChange> files = new ArrayList<>();
        boolean complete = false;
        for (int page = 1; page <= props.maxFilePages(); page++) {
            List<GithubPrFile> raw = fetchFilePage(pr, page);
            if (raw == null || raw.isEmpty()) {
                complete = true;   // 拿到空页说明已翻完
                break;
            }
            for (GithubPrFile f : raw) {
                files.add(toFileChange(f));
            }
            if (raw.size() < FILES_PER_PAGE) {
                complete = true;   // 不满一页即最后一页
                break;
            }
        }
        // 最后一页恰好满载时，无法区分"正好翻完"与"还有下一页"：
        // 多探一页判断是否还有内容（探测结果不加入列表），避免把完整 PR 误报为截断。
        if (!complete) {
            List<GithubPrFile> probe = fetchFilePage(pr, props.maxFilePages() + 1);
            complete = probe == null || probe.isEmpty();
        }
        return new FetchedFiles(files, !complete);
    }

    /** 抓取一页文件列表，并把 GitHub 状态码翻译成领域异常。 */
    private List<GithubPrFile> fetchFilePage(PrUrl pr, int page) {
        String path = GithubApiPaths.pullRequest(pr) + "/files";
        try {
            return github.get()
                    .uri(b -> b.path(path)
                            .queryParam("per_page", FILES_PER_PAGE)
                            .queryParam("page", page)
                            .build())
                    .retrieve()
                    .bodyToMono(PR_FILES)
                    .block(props.timeout());
        } catch (WebClientResponseException e) {
            HttpStatusCode status = e.getStatusCode();
            if (status.value() == 404) {
                throw new GithubPrNotFoundException(pr, e);
            }
            if (status.value() == 401 || status.value() == 403) {
                throw new GithubAuthException(status.value(), e.getResponseBodyAsString(), e);
            }
            throw new GithubApiException("GitHub API call failed: " + status, e);
        } catch (RuntimeException e) {
            // 只有传输层故障才是上游问题；空指针、非法状态等编程错误必须原样抛出，
            // 否则会被伪装成 502，排障时找不到真正的原因。
            if (!isTransportFailure(e)) throw e;
            throw new GithubApiException("GitHub API call failed: " + e.getClass().getSimpleName(), e);
        }
    }

    /** 传输层故障：连接失败、响应超时、解码缓冲超限。 */
    private static boolean isTransportFailure(RuntimeException e) {
        return e instanceof WebClientRequestException
                || e instanceof DataBufferLimitException
                || (e instanceof IllegalStateException && e.getMessage() != null
                    && e.getMessage().startsWith("Timeout on blocking read"));
    }

    /**
     * 抓取 PR 标题。传输故障时返回 null 让管线优雅降级——标题只是提示，不是必需输入；
     * 编程错误仍然抛出，不被静默吞掉。
     */
    public String fetchPrTitle(PrUrl pr) {
        try {
            PrMetadata meta = github.get()
                    .uri(GithubApiPaths.pullRequest(pr))
                    .retrieve()
                    .bodyToMono(PrMetadata.class)
                    .block(props.timeout());
            return meta != null ? meta.title() : null;
        } catch (RuntimeException e) {
            if (!isTransportFailure(e)) throw e;
            log.warn("Failed to fetch PR title: type={}", e.getClass().getSimpleName());
            return null;
        }
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private record PrMetadata(String title) {}

    /** GitHub 原始条目 → 内部 FileChange；无 patch 且非删除文件按二进制处理。 */
    private FileChange toFileChange(GithubPrFile f) {
        boolean binary = f.patch() == null && !"removed".equals(f.status());
        return new FileChange(
                f.filename(),
                f.status(),
                f.additions(),
                f.deletions(),
                binary,
                f.patch(),
                binary ? List.of() : diffParser.parse(f.patch())
        );
    }
}
