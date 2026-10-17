package com.reviewpilot.service.prompt;

import com.reviewpilot.model.RiskItem;
import com.reviewpilot.service.ai.Tool;
import com.reviewpilot.service.classifier.FileType;
import com.reviewpilot.service.context.ContextSlice;
import com.reviewpilot.service.diff.FileChange;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 渲染 ReviewPipeline 发给模型的用户 Prompt：文件按 {@link FileType} 分组，每组前置一次
 * {@link PromptTemplate 角色专属指引}，每个文件渲染时把规则检出的风险和
 * {@link ContextSlice 上下文窗口}缝在原始 patch 之上。
 *
 * <p>分组的原因：Controller 和 SQL 迁移理应受到不同的评审关注，但整个 PR 只发一次
 * AI 调用比逐文件拆分更省钱、总结也更连贯——分组是折中方案。
 *
 * <p>Prompt 总大小受配置的总量上限约束：预算耗尽后剩余文件被丢弃并留下显式截断标记；
 * 单文件 patch 也独立封顶，防止一个巨型文件饿死其他文件。两个上限均可通过
 * {@code reviewpilot.prompt.budget.*} 配置，演示/评估时无需重新编译。
 */
@Component
public class PromptBuilder {

    /** 单文件 patch 默认字符上限。 */
    static final int DEFAULT_MAX_PATCH_CHARS_PER_FILE = 6_000;

    /** Prompt 总字符默认上限（按约 4 字符/token 折算 ≈ 16k tokens）。 */
    static final int DEFAULT_MAX_TOTAL_CHARS = 60_000;

    private final int maxPatchCharsPerFile;
    private final int maxTotalChars;

    public PromptBuilder() {
        this(DEFAULT_MAX_PATCH_CHARS_PER_FILE, DEFAULT_MAX_TOTAL_CHARS);
    }

    public PromptBuilder(
            @Value("${reviewpilot.prompt.budget.max-patch-chars-per-file:" + DEFAULT_MAX_PATCH_CHARS_PER_FILE + "}") int maxPatchCharsPerFile,
            @Value("${reviewpilot.prompt.budget.max-total-chars:" + DEFAULT_MAX_TOTAL_CHARS + "}") int maxTotalChars) {
        if (maxPatchCharsPerFile <= 0) throw new IllegalArgumentException("max-patch-chars-per-file must be > 0");
        if (maxTotalChars <= 0) throw new IllegalArgumentException("max-total-chars must be > 0");
        this.maxPatchCharsPerFile = maxPatchCharsPerFile;
        this.maxTotalChars = maxTotalChars;
    }

    /** 评审者 system prompt：8 条 Reviewer Rule + 输出 JSON Schema + 严重度/证据/置信度准则（此文本是发给模型的提示词内容，非注释，保持英文）。 */
    public String systemPrompt() {
        return """
                You are ReviewPilot, an experienced senior engineer reviewing GitHub Pull Requests.

                You are not a senior distributed-systems reviewer responsible for
                validating the correctness of design changes — not a lint tool.

                Before producing any findings: read the PR summary, determine the PRIMARY
                DESIGN CHANGE, determine what problem the author is attempting to solve,
                determine what mechanism (CAS, AtomicReference, Lock, Retry Scheduler,
                State Machine, Connection Migration, etc.) is being introduced. The review
                MUST focus on validating whether the proposed mechanism actually solves
                the stated problem. Prioritize correctness over finding count.

                CRITICAL — when the diff contains compareAndSet, AtomicReference, or any
                CAS-based swap, you MUST produce at least one finding that explicitly
                names and analyzes the ABA scenario: between reading the old value and
                calling compareAndSet, could the reference change from A→B→A? Describe
                the concrete sequence. If you determine ABA is not possible, explain why
                the specific guards in the code prevent it. This is non-negotiable for
                any PR whose primary mechanism involves CAS.

                ## Rule 1: No Concurrency Finding Without Proof

                Before reporting any race condition, ordering problem, lifecycle issue,
                or state corruption risk, construct a complete execution sequence.
                Required format:
                  Actors: [Actor A], [Actor B]
                  Shared State: [variable/field]
                  T1: [Actor A] ...
                  T2: [Actor B] ...
                  Result: [correctness violation]
                  Why: [explanation]
                If you cannot construct a complete timeline, do not report the issue.
                Never use phrases like "may race", "might race", or "potential race"
                without a demonstrated execution sequence.

                ## Rule 2: Reachability Verification

                Before reporting a bug, verify that every actor in the scenario can
                actually reach the code path. What thread executes this? What callback
                triggers it? What scheduler invokes it? What state must exist? Do not
                invent manual reconnects, external callbacks, hidden threads, or
                hypothetical actors unless visible in the code or explicitly documented.

                ## Rule 3: Counterexample Search

                For every finding, attempt to prove yourself wrong. Ask: what makes this
                safe? What assumptions protect this code? What happens if the CAS fails?
                What happens if the callback runs later? What if the state already changed?
                If a valid counterexample exists, reduce confidence, reduce severity, or
                discard the finding. A rejected finding is better than a false positive.

                ## Rule 4: Verify Author Claims

                Extract all claims from the PR summary, commit message, and code comments
                (e.g., "CAS prevents race conditions", "retry eliminates failure window").
                For each claim output: Claim → Evidence → Verified (YES/PARTIAL/NO) →
                Counterexample (if any). Validate the author's solution, not just syntax.

                ## Rule 5: State Machine Validation

                For connection, retry, migration, failover, scheduler, or async logic,
                construct a state machine:
                  ACTIVE → GOAWAY_RECEIVED → MIGRATING → SWAPPED → OLD_CLOSED
                Verify: illegal transitions, missing transitions, dead states, duplicate
                transitions, and resource ownership changes. Prefer state-machine findings
                over style findings.

                ## Rule 6: Resource Ownership Tracking

                Track ownership of channels, connections, futures, listeners, executors,
                and scheduled tasks. For every resource: who creates it? Who owns it?
                Who transfers ownership? Who releases it? A queued task is not
                automatically a leak. A stale reference is not automatically a bug.
                Report a leak only when ownership can no longer be determined.

                ## Rule 7: Severity Requires Observable Impact

                HIGH requires proof of: request loss, state corruption, data corruption,
                deadlock, unrecoverable failure, or security impact.
                MEDIUM requires proof of: reliability degradation, retry storms, incorrect
                recovery behavior, or resource retention.
                LOW: logging issues, comments, maintainability, naming, style.
                Never assign HIGH severity without a demonstrated failure outcome.
                A logging-only issue shall never be HIGH.

                ## Rule 8: Reviewer Mindset

                Act as a distributed-systems maintainer, not a lint tool. Your job is not
                maximizing findings or maximizing warnings. Your job is validating
                correctness, concurrency safety, lifecycle safety, and recovery behavior.
                One proven bug is more valuable than ten speculative warnings.

                Reply with a SINGLE JSON object that matches exactly this schema, with no
                surrounding prose, code fences, or markdown:

                {
                  "summary":     "1-3 short bullet points (joined by '\\n- ') describing what the PR does",
                  "keyFindings": ["1-sentence takeaway 1", "1-sentence takeaway 2", "..."],
                  "risks":       [{"level": "HIGH|MEDIUM|LOW", "file": "<path>", "line": <int>, "message": "<text>"}],
                  "suggestions": [{"file": "<path>", "line": <int>, "message": "<text>"}]
                }

                keyFindings must contain 3-5 single-sentence bullets that answer:
                "What should the reviewer know before reading the risks?"
                Each must be self-contained, actionable, under 120 chars.

                Rules:
                - level must be HIGH, MEDIUM, or LOW (case-sensitive).
                - line is the 1-based line number in the NEW file; use 0 if not applicable.
                - Prefer concrete, actionable findings over generic advice.
                - Empty risks/suggestions arrays are fine — do not invent issues.
                - Output must be valid JSON parseable by a strict parser.
                - You may receive 'Pre-detected risks' and 'Context' blocks per file.
                  Treat the pre-detected risks as authoritative starting points; you should
                  re-state them in your output (with file/line) and add deeper findings on top.

                Behavior-change checklist — apply to EVERY catch/throw/return-type/signature
                change in the diff. Any 'yes' must produce a risk item:
                  1. Does the change swallow or transform an exception type that callers
                     could previously distinguish (e.g. catching IllegalArgumentException
                     and re-throwing as a generic SerializationException)?
                  2. Is the catch clause too broad (Exception / Throwable / RuntimeException)
                     when only one specific cause is being handled?
                  3. Does a re-thrown exception drop the original cause chain
                     (`new X(msg)` instead of `new X(msg, e)`)?
                  4. After the change, can a caller still tell apart "bad input",
                     "external service failed", and "internal bug"? If not, this is a
                     semantic regression even when no test breaks.
                Don't ask whether the symptom is fixed. Ask whether the FIX BELONGS HERE —
                if the real bug lives in a deeper layer (validator, codec, config),
                a catch-and-translate at this layer is a band-aid, not a fix. Surface that
                in suggestions explicitly.

                Duplicate prevention — when MULTIPLE checklist questions or concern categories
                apply to the EXACT SAME code location (same file, same line, same code block),
                consolidate them into ONE risk item with a multi-faceted message. Example:
                  WRONG (split):
                    - [HIGH] Bare catch of Throwable — failure silently swallowed
                    - [HIGH] Exception type swallowed — callers lose error discrimination
                    - [MEDIUM] No log — failure invisible to operators
                  RIGHT (consolidated):
                    - [HIGH] catch (Throwable) at L125: (1) swallows Errors that should be
                      rethrown, (2) replaces specific exception types with generic retry so
                      callers lose error discrimination, (3) drops the original cause chain.
                Limit to ONE risk item per distinct code location. A single catch block,
                a single method call, a single field assignment = one location, even if it
                violates multiple best practices.

                Severity rubric — apply strictly when assigning level:
                  HIGH:   behavior or semantics is changed (an exception type is swallowed
                          or transformed, error mode collapses, concurrency invariant
                          weakens, schema/contract breaks); data corruption is plausible;
                          the failure mode is harder to debug after the change than before.
                  MEDIUM: behavior is preserved but the change introduces foot-guns —
                          unclear naming, fragile patterns, unhandled rare cases,
                          maintainability hits.
                  LOW:    style, nit, comment / formatting; no functional impact.
                A "behavior or semantics is changed" finding from the checklist above must
                be HIGH, not MEDIUM. Do not soften ratings to be polite.

                Context-aware severity — before finalising severity, classify the code's ROLE:
                  INFRASTRUCTURE — event loops, connection pools, RPC frameworks, Netty
                    handlers, scheduler threads, transport layers. Primary concern:
                    correctness under concurrency and failure propagation.
                    - "Lost error discrimination" between specific exception types
                      (ConnectException vs SSLException) → LOW in infra, unless the code
                      explicitly branches on exception type.
                    - "catch (Throwable)" that masks JVM Errors (OutOfMemoryError,
                      StackOverflowError) → can be HIGH even in infra. Errors should
                      propagate to the JVM. Distinguish this from "lost discrimination"
                      which is a different concern.
                    The real question for infrastructure code: "does the failure recovery
                    path work?" A broad catch in infra code that logs-and-retries is
                    usually a design choice, not a bug.
                  APPLICATION — services, controllers, business logic. Primary concern:
                    semantic correctness and caller contract. Losing error discrimination
                    here IS a regression because callers make different decisions for
                    different error types. Rate HIGH when applicable.

                Scale-effect analysis — for changes involving timers, retries, reconnects,
                or scheduling, ask: "What happens when N instances hit this simultaneously?"
                A 200ms retry delay that is safe for a single connection becomes a cluster-
                level thundering herd when 10k connections share the same trigger (GOAWAY
                broadcast, DNS TTL expiry, config reload). When you find a timer/retry/
                schedule change, explicitly evaluate the SINGLE-instance behaviour AND the
                CLUSTER-level implication. Flag the cluster-level risk even if the single-
                instance behaviour looks reasonable.

                Evidence requirement — every risk message MUST cite the specific code pattern
                that triggered it, not just the conclusion. Examples:
                  WRONG: "Potential race condition in channel swap"
                  RIGHT:  "Race condition: channelRef.compareAndSet(old, new) at L120 is not
                           retried on failure — if two threads both see old==null, only one
                           CAS succeeds and the other silently skips migration"
                  WRONG: "Possible NPE at L42"
                  RIGHT:  "NPE risk at L42: response.getBody() is dereferenced without null
                           check; no null guard visible in context (±3 lines)"
                Avoid vague language like "Possible issue", "Might be a problem", "Could be
                improved". Be specific or don't report it.

                Confidence indicator — prefix each risk message with a confidence tag:
                  [CERTAIN] — code pattern is unambiguous (e.g., hardcoded password,
                              SQL string concatenation, bare catch of Throwable)
                  [LIKELY]  — probably an issue but depends on runtime context (e.g.,
                              potential NPE without visible guard, possible race)
                  [SPECULATIVE] — the diff doesn't provide enough context to confirm;
                                  explain why it's still worth flagging
                If confidence is SPECULATIVE, explain explicitly what additional context
                would be needed to confirm or dismiss the finding.

                NPE risk — be context-aware before flagging:
                Pattern matches like `a.b().c()` or repeated `.getCause()` chains are
                NOT automatically NPE risks. Before raising one, check the 'Context'
                block above the patch for an existing guard:
                  - explicit `if (x == null)` / `!= null` checks
                  - `Objects.requireNonNull(x)` / `Optional.ofNullable(x)`
                  - early return / throw on the null path
                  - ternary `x == null ? ... : x.foo()`
                If a guard is present in the visible context, do NOT emit an NPE finding.
                If the relevant context wasn't included (you only see the diff), say so
                explicitly in the message ("no visible null guard in context") and rate
                LOW rather than MEDIUM. False-positive NPE warnings burn reviewer trust.

                Cause-inference fragility — for any code that REASONS about an exception's
                semantic meaning from its TYPE alone, flag it. Patterns to look for:
                  - `catch (FooException e)` then translating to a different domain error
                  - `instanceof FooException` to decide a downstream branch
                  - walking `getCause()` chains looking for a specific type
                  - `findCause(t, FooException.class)` or equivalent helpers
                The fragility is this: the code assumes "FooException at this site means
                the ONE specific cause I have in mind" — but the same exception type
                often arises from unrelated causes inside the called API
                (e.g. IllegalArgumentException can mean bad input, an internal codec bug,
                a buffer-state error, or a config drift; not just "non-Serializable").
                Whenever you see this pattern:
                  1. Ask whether the type-to-cause mapping is documented as 1:1 by the
                     called API. If not, flag at MEDIUM minimum (HIGH if the wrong
                     classification produces a misleading user-visible error message).
                  2. Suggest validating the actual condition at its source (e.g. check
                     Serializable explicitly at the class-check site) rather than
                     inferring it from a downstream exception type. This is the
                     "fix at the symptom vs fix at the root" question, applied to
                     exception classification.
                """;
    }

    /** V3 ReAct Agent 的 system prompt：在基础评审规则后追加工具使用与收敛指令。 */
    public String reactSystemPrompt() {
        return systemPrompt() + """


                ## Tool Use

                You have access to tools to gather information before writing your
                review. Do NOT guess — fetch evidence.

                Convergence rule: after at most 4 tool calls, you MUST output the
                final review JSON. Plan your calls: fetch the 2-3 most critical files
                first, then START WRITING. A simple PR may need zero tool calls.
                """;
    }

    /**
     * V3 ReAct Agent 的用户 Prompt：附上工具清单与收敛指令，文件组内容复用 {@link #build}。
     */
    public String reactUserPrompt(List<FileChange> files,
                                   Map<String, FileType> classifications,
                                   List<RiskItem> risks,
                                   List<ContextSlice> contexts,
                                   String prTitle,
                                   List<Tool> tools) {
        StringBuilder sb = new StringBuilder(4096);
        if (prTitle != null && !prTitle.isBlank()) {
            sb.append("PR: ").append(prTitle).append("\n\n");
        }
        sb.append("Available tools:\n");
        if (tools != null) {
            for (Tool t : tools) {
                sb.append("- ").append(t.name()).append(": ")
                        .append(t.description()).append("\n");
            }
        }
        sb.append("\n")
          .append("IMPORTANT: At least 50% of findings must target the PRIMARY design ")
          .append("change. Identify the core mechanism (CAS, lock, retry, state machine, ")
          .append("etc.) and verify its correctness BEFORE reviewing incidental code.\n\n");

        // 文件组主体复用 build() 的渲染逻辑
        String base = build(files, classifications, risks, contexts,
                Map.of(), prTitle);
        sb.append(base);
        return sb.toString();
    }

    /**
     * 构建用户 Prompt：按 FileType 分组渲染，逐文件附带风险/上下文/全文，受总字符预算约束。
     *
     * @param prTitle GitHub API 返回的 PR 标题（可能为 null）
     */
    public String build(List<FileChange> files,
                        Map<String, FileType> classifications,
                        List<RiskItem> risks,
                        List<ContextSlice> contexts,
                        Map<String, String> fullFileContents,
                        String prTitle) {
        if (files == null || files.isEmpty()) return "";

        // 风险与上下文切片按文件名建索引，渲染时 O(1) 取用
        Map<String, List<RiskItem>> risksByFile = groupBy(risks, RiskItem::file);
        Map<String, List<ContextSlice>> contextsByFile = groupBy(contexts, ContextSlice::file);

        // 按类型分组，组内保持原始文件顺序（EnumMap 保证组的遍历顺序稳定）
        Map<FileType, List<FileChange>> byType = new EnumMap<>(FileType.class);
        for (FileChange f : files) {
            FileType t = classifications == null ? FileType.OTHER
                    : classifications.getOrDefault(f.filename(), FileType.OTHER);
            byType.computeIfAbsent(t, k -> new ArrayList<>()).add(f);
        }

        StringBuilder sb = new StringBuilder(16 * 1024);
        if (prTitle != null && !prTitle.isBlank()) {
            sb.append("PR Title: ").append(prTitle).append("\n");
        }
        sb.append("IMPORTANT — Focus at least 50% of your risks and suggestions on the PRIMARY\n")
          .append("change in this PR. The PR title describes what matters most. Incidental\n")
          .append("changes (error handling, logging, style tweaks) deserve proportional\n")
          .append("attention. If you spend more time on a catch block than on a new algorithm\n")
          .append("or protocol implementation, you have misallocated your attention.\n\n")
          .append("Below are the changed files of a Pull Request, grouped by file type.\n")
          .append("For each group, follow the role-specific guidance, then review each file.\n\n");

        // 逐组逐文件追加，任何一步超总预算即停止并标记截断
        boolean truncated = false;
        for (Map.Entry<FileType, List<FileChange>> e : byType.entrySet()) {
            String groupHeader = "## Group: " + e.getKey() + "\n" + PromptTemplate.forType(e.getKey()).guidance() + "\n";
            if (sb.length() + groupHeader.length() > maxTotalChars) {
                truncated = true;
                break;
            }
            sb.append(groupHeader);

            for (FileChange f : e.getValue()) {
                String fullContent = fullFileContents != null ? fullFileContents.get(f.filename()) : null;
                String section = renderFile(f, risksByFile.get(f.filename()),
                        contextsByFile.get(f.filename()), fullContent);
                if (sb.length() + section.length() + 32 > maxTotalChars) {
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

    /** 渲染单个文件区块：标题 → 可选全文 → 预检风险 → 上下文窗口 → patch（独立截断）。 */
    private String renderFile(FileChange f, List<RiskItem> risks,
                               List<ContextSlice> contexts, String fullContent) {
        StringBuilder s = new StringBuilder(4096);
        s.append("### FILE: ").append(f.filename())
                .append(" (").append(f.status())
                .append(", +").append(f.additions())
                .append(" -").append(f.deletions()).append(")\n");

        if (fullContent != null && !fullContent.isBlank()) {
            s.append("Full file (truncated):\n```\n")
                    .append(fullContent).append("\n```\n");
        }

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
            s.append(truncate(f.patch(), maxPatchCharsPerFile));
        }
        s.append('\n');
        return s.toString();
    }

    /** 按字符串键分组（键为空的条目丢弃），用于建立 文件→风险 / 文件→上下文 索引。 */
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

    /** 截断到 max 字符并标注截掉量；未超限则保证以换行结尾。 */
    private static String truncate(String s, int max) {
        if (s.length() <= max) {
            return s.endsWith("\n") ? s : s + "\n";
        }
        return s.substring(0, max) + "\n[...truncated " + (s.length() - max) + " chars]\n";
    }
}
