package com.reviewpilot.service.github;

import com.reviewpilot.config.GithubProperties;
import com.reviewpilot.model.PrUrl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 限制本服务允许操作的仓库范围。
 *
 * <p>所有 GitHub 调用都使用服务端自己的 {@code GITHUB_TOKEN}，而仓库由调用方挑选——
 * 这是典型的混淆代理（confused deputy）风险。空白名单保持现状行为（任意仓库，适合本地
 * 与演示场景）；当持有私有仓库访问权的 Token 暴露给不可信调用方之前，必须先配置它。
 *
 * <p>条目格式为 {@code owner/repo}，两段均可用 {@code *} 通配，
 * 如 {@code octocat/Hello-World}、{@code octocat/*}、{@code spring-*&#47;*}。
 */
@Component
public class RepoAllowlist {

    private static final Logger log = LoggerFactory.getLogger(RepoAllowlist.class);

    private final List<Pattern> patterns;

    public RepoAllowlist(GithubProperties props) {
        this.patterns = compile(props.allowedRepos());
        if (patterns.isEmpty()) {
            // 有 Token 却无白名单：服务可代调用方读取任意仓库，启动时告警提醒
            if (!props.token().isBlank()) {
                log.warn("GITHUB_TOKEN is set but reviewpilot.github.allowed-repos is empty: "
                        + "this service can read, with its own token, any repository a caller "
                        + "names. Set allowed-repos, or use a token without private-repo access.");
            }
        } else {
            log.info("Repository allowlist active: {}", props.allowedRepos());
        }
    }

    /** @throws RepoNotAllowedException PR 所属仓库不在白名单内时抛出 */
    public void requireAllowed(PrUrl pr) {
        if (patterns.isEmpty()) {
            return;   // 空白名单 = 不限制
        }
        String target = pr.owner() + "/" + pr.repo();
        boolean allowed = patterns.stream().anyMatch(p -> p.matcher(target).matches());
        if (!allowed) {
            log.warn("Refused {}/{} — outside reviewpilot.github.allowed-repos", pr.owner(), pr.repo());
            throw new RepoNotAllowedException(target);
        }
    }

    /** 把 owner/repo 通配条目编译为正则：仅 '*' 是通配符，其余字符全部字面量化。 */
    private static List<Pattern> compile(List<String> entries) {
        List<Pattern> out = new ArrayList<>();
        if (entries == null) {
            return out;
        }
        for (String entry : entries) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            StringBuilder regex = new StringBuilder();
            for (char c : entry.trim().toCharArray()) {
                // 只有 '*' 是通配符；其余一律 Pattern.quote 转义，
                // 这样 "a.b/repo" 里的点不会退化成能匹配 "axb/repo" 的元字符
                regex.append(c == '*' ? ".*" : Pattern.quote(String.valueOf(c)));
            }
            out.add(Pattern.compile(regex.toString()));
        }
        return out;
    }
}
