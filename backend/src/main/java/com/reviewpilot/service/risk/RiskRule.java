package com.reviewpilot.service.risk;

import com.reviewpilot.model.RiskItem;
import com.reviewpilot.service.classifier.FileType;
import com.reviewpilot.service.diff.FileChange;

import java.util.List;

/**
 * 不依赖 AI 的静态检查：扫描单个 {@link FileChange} 中某一类问题（锁未释放、裸 catch、
 * SQL 字符串拼接等），并输出钉在相关行上的 {@link RiskItem}。
 *
 * <p>每条规则都是一个 Spring {@code @Component}，{@code RiskDetector} 通过构造器注入的
 * {@code List<RiskRule>} 自动收集。新增规则 = 往 {@code service/risk/rules/} 放一个新
 * {@code @Component} 类——无需维护中心注册表。
 *
 * <h3>所有规则共同遵守的约定</h3>
 * <ul>
 *   <li>{@link #scan(FileChange)} 只检查 {@code ADDED} diff 行。
 *       碰存量代码会标记 PR 作者并未引入的问题，那是噪音。</li>
 *   <li>{@link RiskItem#line()} 使用 diff 中的新文件行号；0 表示"文件级、无具体行"。</li>
 *   <li>{@link #appliesTo(FileType)} 是廉价的前置过滤器，
 *       避免把 SQL 注入规则跑到 Markdown 文件上。</li>
 *   <li>规则遇到畸形输入不得抛异常。RiskDetector 虽有 try/catch + 日志兜底，
 *       防御性规则能保持日志干净。</li>
 * </ul>
 *
 * <p>规则层刻意是启发式、只看 patch 的——不解析 Java 源码。目的是廉价地抓住明显的
 * 坑，随后（PR#6）喂进 Prompt，让 AI 的评审一开始就带着先验信息。
 */
public interface RiskRule {

    /** 稳定标识符，用于日志（及后续对 AI 建议的去重）。 */
    String id();

    /**
     * 该规则对给定文件类型是否可能产出有用发现。
     * 默认适用于所有类型；可覆写收窄到如 {@link FileType#CONTROLLER} / {@link FileType#SERVICE}。
     */
    default boolean appliesTo(FileType type) {
        return true;
    }

    /**
     * 扫描文件 hunks 产出发现。实现应遍历 {@code change.hunks()} 且只处理
     * {@code ADDED} 行。无命中时返回空列表——绝不返回 null。
     */
    List<RiskItem> scan(FileChange change);
}
