package com.reviewpilot.service.ai;

/**
 * LLM 文本回复在 JSON 解析前的共享清洗工具：去代码围栏、提取 JSON 对象体，
 * 供主评审流程与 Critic 流程复用，避免两份实现产生分歧。
 */
public final class JsonReplyCleaner {

    private JsonReplyCleaner() {}

    /** 剥离 ```json / ``` / ~~~ 代码围栏，容忍尾部空白与附加文本。 */
    public static String stripFences(String s) {
        if (s == null) return "";
        String t = s.trim();
        // 处理 ~~~json ... ~~~（少数模型使用波浪线围栏）
        if (t.startsWith("~~~")) {
            int nl = t.indexOf('\n');
            if (nl > 0) t = t.substring(nl + 1).trim();
            int end = t.lastIndexOf("~~~");
            if (end >= 0) t = t.substring(0, end).trim();
        }
        // 处理 ```json ... ```（最常见形式，while 循环容忍重复围栏）
        while (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            if (nl < 0) { t = t.substring(3).trim(); break; }
            t = t.substring(nl + 1);
            int end = t.lastIndexOf("```");
            if (end >= 0) t = t.substring(0, end);
            t = t.trim();
        }
        return t;
    }

    /**
     * 从可能夹带前后散文的文本（如 "Here is the JSON: {...}"）中切出 JSON 对象体：
     * 取第一个 '{' 到最后一个 '}' 的子串；若缺失或顺序颠倒则原样返回输入。
     */
    public static String extractJsonObject(String s) {
        if (s == null || s.isEmpty()) return "";
        int first = s.indexOf('{');
        int last = s.lastIndexOf('}');
        if (first < 0 || last <= first) return s;
        return s.substring(first, last + 1);
    }
}
