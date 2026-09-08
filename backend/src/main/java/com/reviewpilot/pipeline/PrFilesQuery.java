package com.reviewpilot.pipeline;

import com.reviewpilot.model.PrUrl;
import com.reviewpilot.service.github.FetchedFiles;
import com.reviewpilot.service.github.GithubPrFetcher;
import com.reviewpilot.service.github.RepoAllowlist;
import org.springframework.stereotype.Service;

/** "只看 diff" 用例的应用层查询：解析 URL → 白名单校验 → 拉取并解析 PR 变更文件（不调用 LLM）。 */
@Service
public class PrFilesQuery {
    private final GithubPrFetcher fetcher;
    private final RepoAllowlist allowlist;

    public PrFilesQuery(GithubPrFetcher fetcher, RepoAllowlist allowlist) {
        this.fetcher = fetcher;
        this.allowlist = allowlist;
    }

    /** 编排 diff-only 流程：URL 校验在前，仓库白名单其次，最后抓取文件列表。 */
    public FetchedFiles files(String rawUrl) {
        PrUrl pr = PrUrl.parse(rawUrl);
        allowlist.requireAllowed(pr);
        return fetcher.fetchFiles(pr);
    }
}
