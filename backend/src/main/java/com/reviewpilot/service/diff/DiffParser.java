package com.reviewpilot.service.diff;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把 GitHub PR files API 返回的 unified diff {@code patch} 解析为带行号标注的 {@link DiffHunk} 列表。
 * <p>
 * 解析器刻意只接受 GitHub 逐文件 patch 输出的 hunk 级子集：一串 {@code @@ ... @@} 头，
 * 后跟以 {@code ' '}、{@code '+'}、{@code '-'} 开头的行。文件级头
 * （{@code diff --git}、{@code --- a/...}、{@code +++ b/...}）不会出现在 patch 字段里，
 * 因此不在此处理。"{@code \ No newline at end of file}" 标记被静默跳过。
 */
@Component
public class DiffParser {

    private static final Pattern HUNK_HEADER = Pattern.compile(
            "^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*$");

    /** 逐行扫描 patch：以 @@ 头开启一个 hunk，维护新旧两个行号游标。 */
    public List<DiffHunk> parse(String patch) {
        if (patch == null || patch.isEmpty()) {
            return Collections.emptyList();
        }

        List<DiffHunk> hunks = new ArrayList<>();
        // split 时保留结尾空串，确保以空白上下文/新增行结尾的 hunk 不被吞掉
        String[] lines = patch.split("\n", -1);

        int i = 0;
        while (i < lines.length) {
            Matcher m = HUNK_HEADER.matcher(lines[i]);
            if (!m.matches()) {
                i++;
                continue;
            }
            // 省略 ,count 时按惯例视为 1 行
            int oldStart = Integer.parseInt(m.group(1));
            int oldCount = m.group(2) == null ? 1 : Integer.parseInt(m.group(2));
            int newStart = Integer.parseInt(m.group(3));
            int newCount = m.group(4) == null ? 1 : Integer.parseInt(m.group(4));

            List<DiffLine> body = new ArrayList<>();
            int oldLine = oldStart;
            int newLine = newStart;
            i++;

            while (i < lines.length && !lines[i].startsWith("@@")) {
                String raw = lines[i];
                if (raw.startsWith("\\")) {
                    // "\ No newline at end of file" 标记——跳过且不动行号游标
                    i++;
                    continue;
                }
                if (raw.isEmpty()) {
                    // 部分工具会输出空行表示上下文行，按上下文处理
                    body.add(new DiffLine(DiffLineType.CONTEXT, oldLine, newLine, ""));
                    oldLine++;
                    newLine++;
                    i++;
                    continue;
                }
                char prefix = raw.charAt(0);
                String content = raw.substring(1);
                // 按前缀推进对应的行号游标：+ 只动新行号，- 只动旧行号，空格双双推进
                switch (prefix) {
                    case '+' -> {
                        body.add(new DiffLine(DiffLineType.ADDED, 0, newLine, content));
                        newLine++;
                    }
                    case '-' -> {
                        body.add(new DiffLine(DiffLineType.REMOVED, oldLine, 0, content));
                        oldLine++;
                    }
                    case ' ' -> {
                        body.add(new DiffLine(DiffLineType.CONTEXT, oldLine, newLine, content));
                        oldLine++;
                        newLine++;
                    }
                    default -> {
                        // 未知前缀——放弃本 hunk 剩余内容，避免行号错位传染后续解析
                        i = lines.length;
                    }
                }
                i++;
            }
            hunks.add(new DiffHunk(oldStart, oldCount, newStart, newCount, body));
        }
        return hunks;
    }
}
