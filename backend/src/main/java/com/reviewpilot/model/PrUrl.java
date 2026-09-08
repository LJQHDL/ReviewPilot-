package com.reviewpilot.model;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Objects;

/**
 * 解析后的 GitHub PR 地址值对象，拆出 owner/repo/PR 编号并做格式校验。
 * 接受形如 {@code https://github.com/owner/repo/pull/12} 的 URL（允许末尾斜杠、锚点、查询串和首尾空白）。
 */
public record PrUrl(String owner, String repo, int number) {

    /** GitHub 官方 owner/repo 字符集；两者会被拼进对外 API 路径，因此不能放宽校验。 */
    private static final java.util.regex.Pattern NAME =
            java.util.regex.Pattern.compile("[A-Za-z0-9._-]{1,100}");

    /** 紧凑构造器：校验 owner/repo 字符合法且 PR 编号为正整数。 */
    public PrUrl {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(repo, "repo");
        if (owner.isBlank() || repo.isBlank()) {
            throw new IllegalArgumentException("owner/repo must not be blank");
        }
        if (!NAME.matcher(owner).matches() || !NAME.matcher(repo).matches()) {
            throw new IllegalArgumentException("Unexpected owner/repo in PR URL");
        }
        if (number <= 0) {
            throw new IllegalArgumentException("PR number must be positive, got " + number);
        }
    }

    /** 从原始字符串解析 PR URL，任何不合规输入统一抛 IllegalArgumentException（→ 400）。 */
    public static PrUrl parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("PR URL must not be empty");
        }
        URI uri;
        try {
            uri = new URI(raw.trim());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Not a valid URL: " + raw, e);
        }

        String host = uri.getHost();
        if (host == null || !host.equalsIgnoreCase("github.com")) {
            throw new IllegalArgumentException("Only github.com PR URLs are supported, got host: " + host);
        }

        String path = uri.getPath();
        if (path == null) {
            throw new IllegalArgumentException("Missing path in PR URL: " + raw);
        }
        // 期望路径格式 /<owner>/<repo>/pull/<number>
        String[] parts = path.split("/");
        // parts[0] 为空，因为路径以 '/' 开头
        if (parts.length < 5 || !"pull".equals(parts[3])) {
            throw new IllegalArgumentException(
                    "Expected path /<owner>/<repo>/pull/<number>, got: " + path);
        }
        String owner = parts[1];
        String repo = parts[2];
        int number;
        try {
            number = Integer.parseInt(parts[4]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("PR number must be an integer, got: " + parts[4], e);
        }
        return new PrUrl(owner, repo, number);
    }

}
