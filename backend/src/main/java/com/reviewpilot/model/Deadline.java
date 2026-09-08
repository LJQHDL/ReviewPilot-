package com.reviewpilot.model;

/**
 * 单次评审请求的墙钟时间预算，在启动任何后续模型调用前统一检查是否已超时。
 *
 * <p>过去各层只约束自己的 HTTP 调用——ReAct 循环限轮数、Provider 限单次超时并在内部重试——
 * 导致一个请求可能占用 Servlet 线程远超客户端放弃的时间。本类型是唯一的整体预算数字。
 *
 * <p>它限制的是允许<em>开始</em>多少工作，而非总耗时：已发出的调用会按自己的超时跑完，无法抢占。
 * 只有把评审移出请求线程才能让该约束变成精确的。
 *
 * @param expiresAtMillis 绝对截止时间戳，{@code 0} 表示未设置预算
 */
public record Deadline(long expiresAtMillis) {

    /** 不限时的哨兵值。 */
    public static final Deadline NONE = new Deadline(0);

    /** 从当前时刻起加上预算毫秒数生成截止时间；预算非正数时返回 NONE。 */
    public static Deadline of(long budgetMillis) {
        return budgetMillis <= 0 ? NONE : new Deadline(System.currentTimeMillis() + budgetMillis);
    }

    /** 预算是否已耗尽。 */
    public boolean expired() {
        return expiresAtMillis > 0 && System.currentTimeMillis() >= expiresAtMillis;
    }
}
