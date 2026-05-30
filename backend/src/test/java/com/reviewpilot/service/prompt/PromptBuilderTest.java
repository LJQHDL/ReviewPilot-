package com.reviewpilot.service.prompt;

import com.reviewpilot.model.RiskItem;
import com.reviewpilot.model.RiskLevel;
import com.reviewpilot.service.classifier.FileType;
import com.reviewpilot.service.context.ContextSlice;
import com.reviewpilot.service.diff.FileChange;
import org.junit.jupiter.api.Test;

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

    private static FileChange file(String name, String patch) {
        return new FileChange(name, "modified", 1, 0, false, patch, List.of());
    }
}
