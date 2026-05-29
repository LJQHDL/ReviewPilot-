package com.reviewpilot.service.diff;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiffParserTest {

    private final DiffParser parser = new DiffParser();

    @Test
    void empty_or_null_patch_yields_no_hunks() {
        assertTrue(parser.parse(null).isEmpty());
        assertTrue(parser.parse("").isEmpty());
    }

    @Test
    void parses_single_hunk_with_added_removed_and_context() {
        String patch = """
                @@ -1,3 +1,4 @@
                 line1
                -line2
                +line2-new
                +line2.5
                 line3""";

        List<DiffHunk> hunks = parser.parse(patch);
        assertEquals(1, hunks.size());

        DiffHunk h = hunks.get(0);
        assertEquals(1, h.oldStart());
        assertEquals(3, h.oldCount());
        assertEquals(1, h.newStart());
        assertEquals(4, h.newCount());

        List<DiffLine> lines = h.lines();
        assertEquals(5, lines.size());

        // Context: oldLine 1, newLine 1
        assertEquals(DiffLineType.CONTEXT, lines.get(0).type());
        assertEquals(1, lines.get(0).oldLine());
        assertEquals(1, lines.get(0).newLine());

        // Removed: oldLine 2, newLine 0
        assertEquals(DiffLineType.REMOVED, lines.get(1).type());
        assertEquals(2, lines.get(1).oldLine());
        assertEquals(0, lines.get(1).newLine());
        assertEquals("line2", lines.get(1).content());

        // Added: oldLine 0, newLine 2
        assertEquals(DiffLineType.ADDED, lines.get(2).type());
        assertEquals(0, lines.get(2).oldLine());
        assertEquals(2, lines.get(2).newLine());
        assertEquals("line2-new", lines.get(2).content());

        // Added: newLine 3
        assertEquals(DiffLineType.ADDED, lines.get(3).type());
        assertEquals(3, lines.get(3).newLine());

        // Context after addition: oldLine 3, newLine 4
        assertEquals(DiffLineType.CONTEXT, lines.get(4).type());
        assertEquals(3, lines.get(4).oldLine());
        assertEquals(4, lines.get(4).newLine());
    }

    @Test
    void parses_multiple_hunks_with_independent_line_counters() {
        String patch = """
                @@ -1,2 +1,2 @@
                 a
                -b
                +B
                @@ -10,2 +10,2 @@
                 x
                -y
                +Y""";

        List<DiffHunk> hunks = parser.parse(patch);
        assertEquals(2, hunks.size());
        assertEquals(1, hunks.get(0).oldStart());
        assertEquals(10, hunks.get(1).oldStart());

        // Line counter resets per hunk: second hunk's removed line is at oldLine 11.
        DiffLine secondRemoved = hunks.get(1).lines().get(1);
        assertEquals(DiffLineType.REMOVED, secondRemoved.type());
        assertEquals(11, secondRemoved.oldLine());
    }

    @Test
    void accepts_short_form_hunk_header_without_count() {
        // GitHub omits the count when it equals 1.
        String patch = """
                @@ -5 +5 @@
                -old
                +new""";

        List<DiffHunk> hunks = parser.parse(patch);
        assertEquals(1, hunks.size());
        assertEquals(1, hunks.get(0).oldCount());
        assertEquals(1, hunks.get(0).newCount());
    }

    @Test
    void preserves_trailing_text_on_hunk_header_line() {
        // GitHub appends the enclosing function/section after the second @@.
        String patch = """
                @@ -1,1 +1,1 @@ public void foo()
                -a
                +b""";

        List<DiffHunk> hunks = parser.parse(patch);
        assertEquals(1, hunks.size());
        assertEquals(2, hunks.get(0).lines().size());
    }

    @Test
    void skips_no_newline_marker() {
        String patch = """
                @@ -1,1 +1,1 @@
                -a
                \\ No newline at end of file
                +b""";

        List<DiffHunk> hunks = parser.parse(patch);
        assertEquals(1, hunks.size());
        // Marker is dropped: only the - and + lines survive.
        assertEquals(2, hunks.get(0).lines().size());
        assertEquals(DiffLineType.REMOVED, hunks.get(0).lines().get(0).type());
        assertEquals(DiffLineType.ADDED, hunks.get(0).lines().get(1).type());
    }

    @Test
    void content_excludes_diff_prefix_character() {
        String patch = """
                @@ -1,1 +1,1 @@
                -hello
                +HELLO""";

        DiffHunk h = parser.parse(patch).get(0);
        assertEquals("hello", h.lines().get(0).content());
        assertEquals("HELLO", h.lines().get(1).content());
    }
}
