package com.reviewpilot.service.prompt;

import com.reviewpilot.service.diff.FileChange;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Naive prompt builder for PR#3 — concatenates each file's name and raw patch
 * and asks the model for a strictly-shaped JSON answer.
 * <p>
 * PR#6 will replace the body of {@link #build} with file-type-routed templates
 * (Controller, Service, SQL, ...) that consume the output of FileClassifier
 * and RiskDetector. Keeping the seam stable now means PR#6 is a single-class
 * swap.
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
                """;
    }

    public String build(List<FileChange> files) {
        StringBuilder sb = new StringBuilder(8 * 1024);
        sb.append("Below are the changed files of a Pull Request. Each section starts with\n")
          .append("'### FILE: <path> (<status>, +<adds> -<dels>)' followed by the unified-diff\n")
          .append("patch for that file. Binary files are listed without a patch.\n\n");

        for (FileChange f : files) {
            String header = "### FILE: " + f.filename()
                    + " (" + f.status() + ", +" + f.additions() + " -" + f.deletions() + ")\n";
            String patch = f.binary() || f.patch() == null
                    ? "(binary or no patch)\n"
                    : truncate(f.patch(), MAX_PATCH_CHARS_PER_FILE);

            // Stop adding files once we'd overflow the total budget — partial review
            // beats a 4xx 'too long' response.
            if (sb.length() + header.length() + patch.length() + 32 > MAX_TOTAL_CHARS) {
                sb.append("\n[truncated: remaining files omitted to stay within prompt budget]\n");
                break;
            }
            sb.append(header).append(patch).append("\n");
        }
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        if (s.length() <= max) {
            return s.endsWith("\n") ? s : s + "\n";
        }
        return s.substring(0, max) + "\n[...truncated " + (s.length() - max) + " chars]\n";
    }
}
