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
 * Restricts which repositories this service will act on.
 *
 * <p>Every GitHub call uses the server's own {@code GITHUB_TOKEN} while the caller
 * picks the repository — the classic confused deputy. An empty allowlist keeps
 * today's behaviour (any repo; right for local and demo use); set it before
 * pointing a token with private-repo access at a network callers control.
 *
 * <p>Entries are {@code owner/repo} with {@code *} allowed in either segment,
 * e.g. {@code octocat/Hello-World}, {@code octocat/*}, {@code spring-*&#47;*}.
 */
@Component
public class RepoAllowlist {

    private static final Logger log = LoggerFactory.getLogger(RepoAllowlist.class);

    private final List<Pattern> patterns;

    public RepoAllowlist(GithubProperties props) {
        this.patterns = compile(props.allowedRepos());
        if (patterns.isEmpty()) {
            if (!props.token().isBlank()) {
                log.warn("GITHUB_TOKEN is set but reviewpilot.github.allowed-repos is empty: "
                        + "this service can read, with its own token, any repository a caller "
                        + "names. Set allowed-repos, or use a token without private-repo access.");
            }
        } else {
            log.info("Repository allowlist active: {}", props.allowedRepos());
        }
    }

    /** @throws RepoNotAllowedException if this PR's repository is outside the allowlist. */
    public void requireAllowed(PrUrl pr) {
        if (patterns.isEmpty()) {
            return;
        }
        String target = pr.owner() + "/" + pr.repo();
        boolean allowed = patterns.stream().anyMatch(p -> p.matcher(target).matches());
        if (!allowed) {
            log.warn("Refused {}/{} — outside reviewpilot.github.allowed-repos", pr.owner(), pr.repo());
            throw new RepoNotAllowedException(target);
        }
    }

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
                // Only '*' is a wildcard; everything else is literal, so a dot in
                // "a.b/repo" cannot match "axb/repo".
                regex.append(c == '*' ? ".*" : Pattern.quote(String.valueOf(c)));
            }
            out.add(Pattern.compile(regex.toString()));
        }
        return out;
    }
}
