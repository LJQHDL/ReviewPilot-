package com.reviewpilot.service.prompt;

import com.reviewpilot.model.RiskItem;
import com.reviewpilot.service.classifier.FileType;
import com.reviewpilot.service.context.ContextSlice;
import com.reviewpilot.service.diff.FileChange;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders the user prompt that ReviewPipeline sends to the model. Files are
 * grouped by {@link FileType} so the model gets {@link PromptTemplate role-specific
 * guidance} once per group, then each file is rendered with its
 * rule-detected risks and any extracted {@link ContextSlice context windows}
 * stitched in above the raw patch.
 *
 * <p>Why grouped: a controller and a SQL migration deserve different review
 * concerns, but a single AI call for the whole PR is cheaper and produces a
 * more coherent summary than splitting per file. Grouping is the compromise.
 *
 * <p>Total prompt size stays bounded by {@link #MAX_TOTAL_CHARS}; once the
 * budget is hit the remaining files are dropped with an explicit truncation
 * marker. Per-file patches are independently capped at
 * {@link #MAX_PATCH_CHARS_PER_FILE} so a single huge file can't starve the
 * others.
 */
@Component
public class PromptBuilder {

    /** Max characters of patch per file we include in the user prompt. */
    private static final int MAX_PATCH_CHARS_PER_FILE = 6_000;

    /** Hard cap on total prompt size to stay well under the model's input budget. */
    private static final int MAX_TOTAL_CHARS = 60_000;

    public String systemPrompt() {
        return """
                You are ReviewPilot, an experienced senior engineer reviewing GitHub Pull Requests.

                Reply with a SINGLE JSON object that matches exactly this schema, with no
                surrounding prose, code fences, or markdown:

                {
                  "summary":     "1-3 short bullet points (joined by '\\n- ') describing what the PR does",
                  "risks":       [{"level": "HIGH|MEDIUM|LOW", "file": "<path>", "line": <int>, "message": "<text>"}],
                  "suggestions": [{"file": "<path>", "line": <int>, "message": "<text>"}]
                }

                Rules:
                - level must be HIGH, MEDIUM, or LOW (case-sensitive).
                - line is the 1-based line number in the NEW file; use 0 if not applicable.
                - Prefer concrete, actionable findings over generic advice.
                - Empty risks/suggestions arrays are fine — do not invent issues.
                - Output must be valid JSON parseable by a strict parser.
                - You may receive 'Pre-detected risks' and 'Context' blocks per file.
                  Treat the pre-detected risks as authoritative starting points; you should
                  re-state them in your output (with file/line) and add deeper findings on top.
                """;
    }

    /**
     * Build the user prompt.
     *
     * @param files            ordered list of changed files
     * @param classifications  file path → FileType, as produced by FileClassifier;
     *                         missing entries fall back to OTHER
     * @param risks            rule-detected risks across the whole PR; grouped per file
     * @param contexts         code windows around risks; grouped per file
     */
    public String build(List<FileChange> files,
                        Map<String, FileType> classifications,
                        List<RiskItem> risks,
                        List<ContextSlice> contexts) {
        if (files == null || files.isEmpty()) return "";

        Map<String, List<RiskItem>> risksByFile = groupBy(risks, RiskItem::file);
        Map<String, List<ContextSlice>> contextsByFile = groupBy(contexts, ContextSlice::file);

        // Group files by type, preserving the original file order within each group.
        Map<FileType, List<FileChange>> byType = new EnumMap<>(FileType.class);
        for (FileChange f : files) {
            FileType t = classifications == null ? FileType.OTHER
                    : classifications.getOrDefault(f.filename(), FileType.OTHER);
            byType.computeIfAbsent(t, k -> new ArrayList<>()).add(f);
        }

        StringBuilder sb = new StringBuilder(16 * 1024);
        sb.append("Below are the changed files of a Pull Request, grouped by file type.\n")
          .append("For each group, follow the role-specific guidance, then review each file.\n\n");

        boolean truncated = false;
        for (Map.Entry<FileType, List<FileChange>> e : byType.entrySet()) {
            String groupHeader = "## Group: " + e.getKey() + "\n" + PromptTemplate.forType(e.getKey()).guidance() + "\n";
            if (sb.length() + groupHeader.length() > MAX_TOTAL_CHARS) {
                truncated = true;
                break;
            }
            sb.append(groupHeader);

            for (FileChange f : e.getValue()) {
                String section = renderFile(f, risksByFile.get(f.filename()), contextsByFile.get(f.filename()));
                if (sb.length() + section.length() + 32 > MAX_TOTAL_CHARS) {
                    truncated = true;
                    break;
                }
                sb.append(section);
            }
            if (truncated) break;
        }
        if (truncated) {
            sb.append("\n[truncated: remaining files omitted to stay within prompt budget]\n");
        }
        return sb.toString();
    }

    private static String renderFile(FileChange f, List<RiskItem> risks, List<ContextSlice> contexts) {
        StringBuilder s = new StringBuilder(2048);
        s.append("### FILE: ").append(f.filename())
                .append(" (").append(f.status())
                .append(", +").append(f.additions())
                .append(" -").append(f.deletions()).append(")\n");

        if (risks != null && !risks.isEmpty()) {
            s.append("Pre-detected risks (from static rules):\n");
            for (RiskItem r : risks) {
                s.append("  - [").append(r.level()).append("] line ").append(r.line())
                        .append(": ").append(r.message()).append('\n');
            }
        }

        if (contexts != null && !contexts.isEmpty()) {
            s.append("Context (lines around the risks):\n");
            for (ContextSlice c : contexts) {
                s.append("  --- ").append(c.startLine()).append('-').append(c.endLine()).append(" ---\n");
                for (String line : c.lines()) {
                    s.append("  ").append(line).append('\n');
                }
            }
        }

        s.append("Patch:\n");
        if (f.binary() || f.patch() == null) {
            s.append("(binary or no patch)\n");
        } else {
            s.append(truncate(f.patch(), MAX_PATCH_CHARS_PER_FILE));
        }
        s.append('\n');
        return s.toString();
    }

    private static <T> Map<String, List<T>> groupBy(List<T> items, java.util.function.Function<T, String> key) {
        Map<String, List<T>> map = new HashMap<>();
        if (items == null) return map;
        for (T item : items) {
            String k = key.apply(item);
            if (k == null || k.isBlank()) continue;
            map.computeIfAbsent(k, kk -> new ArrayList<>()).add(item);
        }
        return map;
    }

    private static String truncate(String s, int max) {
        if (s.length() <= max) {
            return s.endsWith("\n") ? s : s + "\n";
        }
        return s.substring(0, max) + "\n[...truncated " + (s.length() - max) + " chars]\n";
    }
}
