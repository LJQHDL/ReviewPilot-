package com.reviewpilot.service.prompt;

import com.reviewpilot.model.RiskItem;
import com.reviewpilot.model.RiskLevel;
import com.reviewpilot.service.classifier.FileType;
import com.reviewpilot.service.context.ContextSlice;
import com.reviewpilot.service.diff.FileChange;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
class PromptBuilderTest {

    private final PromptBuilder builder = new PromptBuilder();

    @Test
    void empty_files_returns_empty_prompt() {
        String out = builder.build(List.of(), Map.of(), List.of(), List.of());
        assertTrue(out.isEmpty());
    }

    @Test
    void groups_files_by_type_and_includes_template_guidance() {
        FileChange controller = file("Foo.java", "@@ controller patch");
        FileChange service = file("Bar.java", "@@ service patch");
        Map<String, FileType> types = Map.of(
                "Foo.java", FileType.CONTROLLER,
                "Bar.java", FileType.SERVICE);

        String out = builder.build(List.of(controller, service), types, List.of(), List.of());

        // Both group headers appear.
        assertTrue(out.contains("## Group: CONTROLLER"));
        assertTrue(out.contains("## Group: SERVICE"));
        // Each group's guidance sneaks in (one signature bullet from each template).
        assertTrue(out.contains("Input validation"), "controller guidance present");
        assertTrue(out.contains("Transaction boundaries"), "service guidance present");
        // File headers and patches are rendered.
        assertTrue(out.contains("### FILE: Foo.java"));
        assertTrue(out.contains("### FILE: Bar.java"));
        assertTrue(out.contains("@@ controller patch"));
    }

    @Test
    void embeds_pre_detected_risks_for_the_owning_file() {
        FileChange f = file("A.java", "@@ patch");
        Map<String, FileType> types = Map.of("A.java", FileType.SERVICE);
        List<RiskItem> risks = List.of(
                new RiskItem(RiskLevel.HIGH, "A.java", 42, "lock without unlock"),
                new RiskItem(RiskLevel.LOW, "B.java", 9, "should not appear here"));

        String out = builder.build(List.of(f), types, risks, List.of());

        assertTrue(out.contains("Pre-detected risks"));
        assertTrue(out.contains("[HIGH]"));
        assertTrue(out.contains("line 42"));
        assertTrue(out.contains("lock without unlock"));
        // The risk that belongs to a file we didn't include must not leak in.
        assertFalse(out.contains("should not appear here"));
    }

    @Test
    void embeds_context_slices_under_context_heading() {
        FileChange f = file("A.java", "@@ patch");
        Map<String, FileType> types = Map.of("A.java", FileType.SERVICE);
        ContextSlice slice = new ContextSlice("A.java", 10, 12,
                List.of("  10: foo", "+ 11: bar", "  12: baz"));

        String out = builder.build(List.of(f), types, List.of(), List.of(slice));

        assertTrue(out.contains("Context (lines around the risks):"));
        assertTrue(out.contains("--- 10-12 ---"));
        assertTrue(out.contains("11: bar"));
    }

    @Test
    void unclassified_file_falls_back_to_other_template() {
        // No entry in the classifications map for this file → OTHER template.
        FileChange f = file("notes.md", "@@ patch");
        String out = builder.build(List.of(f), Map.of(), List.of(), List.of());
        assertTrue(out.contains("## Group: OTHER"));
        assertTrue(out.contains("don't match a specific role"));
    }

    @Test
    void binary_file_renders_a_marker_instead_of_patch() {
        FileChange f = new FileChange("logo.png", "added", 0, 0, true, null, List.of());
        String out = builder.build(List.of(f), Map.of("logo.png", FileType.OTHER),
                List.of(), List.of());
        assertTrue(out.contains("(binary or no patch)"));
    }

    @Test
    void per_file_patch_exceeding_budget_is_truncated_with_marker() {
        // Build a patch larger than the per-file 6_000 char cap. The renderer
        // must truncate it (not silently include or drop it) so a single huge
        // file can't blow the total prompt budget.
        String huge = "@@\n" + "x".repeat(20_000);
        FileChange f = file("Big.java", huge);

        String out = builder.build(List.of(f), Map.of("Big.java", FileType.OTHER),
                List.of(), List.of());

        assertTrue(out.contains("[...truncated"), "per-file truncation marker missing");
        assertFalse(out.contains("x".repeat(20_000)), "untruncated patch should not appear in full");
    }

    @Test
    void total_budget_exceeded_drops_remaining_files_with_marker() {
        // Build enough files to overflow MAX_TOTAL_CHARS (60_000). Each file
        // here carries a ~6_000-char patch (right at the per-file cap), so a
        // dozen of them comfortably exceed the total budget.
        String big = "@@\n" + "y".repeat(6_000);
        List<FileChange> files = new ArrayList<>();
        Map<String, FileType> types = new HashMap<>();
        for (int i = 0; i < 20; i++) {
            String name = "F" + i + ".java";
            files.add(file(name, big));
            types.put(name, FileType.OTHER);
        }

        String out = builder.build(files, types, List.of(), List.of());

        assertTrue(out.contains("remaining files omitted to stay within prompt budget"),
                "total-budget truncation marker missing");
        // The first file's header must be present; at least one tail file must not be.
        assertTrue(out.contains("### FILE: F0.java"), "first file should have been included");
        assertFalse(out.contains("### FILE: F19.java"), "last file should have been dropped");
    }

    @Test
    void system_prompt_includes_behavior_change_checklist() {
        // Lock the PR#9 prompt strengthening: a future edit must not silently
        // drop the semantic-change guidance that's the whole point of c1+c2.
        String sys = builder.systemPrompt();
        assertTrue(sys.contains("Behavior-change checklist"),
                "system prompt must keep the behavior-change checklist header");
        assertTrue(sys.contains("swallow or transform an exception type"),
                "system prompt must keep the exception-swallow question");
        assertTrue(sys.contains("FIX BELONGS HERE"),
                "system prompt must keep the root-cause prompt");
    }

    @Test
    void system_prompt_includes_severity_rubric() {
        String sys = builder.systemPrompt();
        assertTrue(sys.contains("Severity rubric"),
                "system prompt must keep the severity rubric header");
        assertTrue(sys.contains("must be HIGH"),
                "rubric must enforce HIGH for behavior-change findings");
        assertTrue(sys.contains("Do not soften ratings"),
                "rubric must keep the explicit anti-softening reminder");
    }

    @Test
    void custom_budget_truncates_at_lower_limit() {
        // Demo / evaluator path: bump down the per-file cap to 200 chars and
        // confirm a 1k-char patch trips the truncation marker. Lock the new
        // configurability so a regression that hard-codes the limits is caught.
        PromptBuilder small = new PromptBuilder(200, 5_000);
        FileChange f = file("Big.java", "@@\n" + "x".repeat(1_000));

        String out = small.build(List.of(f), Map.of("Big.java", FileType.OTHER),
                List.of(), List.of());

        assertTrue(out.contains("[...truncated"),
                "custom per-file cap must still emit truncation marker");
        assertFalse(out.contains("x".repeat(1_000)),
                "patch larger than custom cap must not appear in full");
    }

    @Test
    void invalid_budget_rejected_at_construction() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new PromptBuilder(0, 1_000),
                "zero per-file cap should be rejected");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new PromptBuilder(1_000, -1),
                "negative total cap should be rejected");
    }

    private static FileChange file(String name, String patch) {
        return new FileChange(name, "modified", 1, 0, false, patch, List.of());
    }
}
