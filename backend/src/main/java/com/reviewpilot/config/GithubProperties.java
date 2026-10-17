package com.reviewpilot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * 绑定 application.yml 中 {@code reviewpilot.github.*} 配置项的不可变记录。
 *
 * @param apiBase      GitHub REST API 基地址（GitHub Enterprise 可覆盖）
 * @param token        个人访问令牌，空字符串表示匿名访问（60 次/小时限流）
 * @param allowedRepos 本服务允许操作的 {@code owner/repo} 模式列表，为空表示不限；
 *                     所有 GitHub 调用都以服务端自己的 Token 代表匿名调用方发起，
 *                     这是对该 Token 可被指向范围的唯一约束
 * @param timeout      单次调用的 HTTP 上限；本应用每次 GitHub 调用都占一个 Servlet 线程，
 *                     该超时防止一条卡死的连接永久占用工作线程
 * @param maxFilePages 最多翻几页 {@code /pulls/{n}/files}（每页 100 个文件）后放弃；
 *                     触顶时评审结果标记为截断，而不是静默地只评审一部分
 */
@ConfigurationProperties("reviewpilot.github")
public record GithubProperties(String apiBase, String token, List<String> allowedRepos,
                               Duration timeout, int maxFilePages) {

    /** 紧凑构造器：为缺失或非法的配置值填充默认值。 */
    public GithubProperties {
        if (apiBase == null || apiBase.isBlank()) {
            apiBase = "https://api.github.com";
        }
        if (token == null) {
            token = "";
        }
        if (allowedRepos == null) {
            allowedRepos = List.of();
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            timeout = Duration.ofSeconds(10);
        }
        if (maxFilePages <= 0) {
            maxFilePages = 3;
        }
    }
}
