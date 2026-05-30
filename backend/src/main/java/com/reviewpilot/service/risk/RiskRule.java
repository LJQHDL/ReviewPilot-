package com.reviewpilot.service.risk;

import com.reviewpilot.model.RiskItem;
import com.reviewpilot.service.classifier.FileType;
import com.reviewpilot.service.diff.FileChange;

import java.util.List;

/**
 * Static, AI-free check that scans a single {@link FileChange} for a specific
 * class of issue (unreleased lock, bare catch, SQL string concatenation, etc.)
 * and emits {@link RiskItem}s pinned to the relevant lines.
 *
 * <p>Each rule is a Spring {@code @Component} so {@code RiskDetector} can pick
 * them up via constructor-injected {@code List<RiskRule>}. Adding a new rule
 * means dropping a new {@code @Component} class into
 * {@code service/risk/rules/} — no central registry to update.
 *
 * <h3>Conventions all rules follow</h3>
 * <ul>
 *   <li>{@link #scan(FileChange)} only inspects {@code ADDED} diff lines.
 *       Touching pre-existing code would flag issues the PR author didn't
 *       introduce, which is noise.</li>
 *   <li>{@link RiskItem#line()} uses the new-file line number from the diff.
 *       0 means "file-level, no specific line".</li>
 *   <li>{@link #appliesTo(FileType)} is the cheap up-front filter so we don't,
 *       say, run the SQL-injection rule against a Markdown file.</li>
 *   <li>Rules must not throw on malformed input. RiskDetector wraps each call
 *       in try/catch + log, but a defensive rule keeps logs clean.</li>
 * </ul>
 *
 * <p>The rule layer is deliberately heuristic and patch-only — it doesn't parse
 * Java source. The point is to catch obvious foot-guns cheaply and feed them
 * into the prompt later (PR#6) so the AI's review starts already informed.
 */
public interface RiskRule {

    /** Stable identifier used in logs and (later) for de-duping AI suggestions. */
    String id();

    /**
     * Whether this rule can produce useful findings for the given file type.
     * Default: applies to everything; override to narrow to e.g. only
     * {@link FileType#CONTROLLER} / {@link FileType#SERVICE}.
     */
    default boolean appliesTo(FileType type) {
        return true;
    }

    /**
     * Scan the file's hunks for findings. Implementations should iterate
     * {@code change.hunks()} and only act on {@code ADDED} lines. Return an
     * empty list when nothing matches — never null.
     */
    List<RiskItem> scan(FileChange change);
}
