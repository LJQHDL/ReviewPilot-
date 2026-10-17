package com.reviewpilot.service.diff;

import com.reviewpilot.service.github.FetchedFiles;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DiffSnapshotTest {
    @Test
    void capturesImmutableSnapshotAtEveryLevel() {
        var line = new DiffLine(DiffLineType.ADDED, 0, 1, "code");
        var lines = new ArrayList<>(List.of(line));
        var hunk = new DiffHunk(0, 0, 1, 1, lines);
        var hunks = new ArrayList<>(List.of(hunk));
        var file = new FileChange("A.java", "added", 1, 0, false, "+code", hunks);
        var files = new ArrayList<>(List.of(file));
        var fetched = FetchedFiles.complete(files);
        var truncated = new FetchedFiles(files, true);

        lines.clear();
        hunks.clear();
        files.clear();

        assertAll(
                () -> assertEquals(List.of(line), hunk.lines()),
                () -> assertEquals(List.of(hunk), file.hunks()),
                () -> assertEquals(List.of(file), fetched.files()),
                () -> assertEquals(List.of(file), truncated.files()),
                () -> assertTrue(truncated.truncated()),
                () -> assertThrows(UnsupportedOperationException.class, () -> hunk.lines().clear()),
                () -> assertThrows(UnsupportedOperationException.class, () -> file.hunks().clear()),
                () -> assertThrows(UnsupportedOperationException.class, () -> fetched.files().clear()));
    }
}
