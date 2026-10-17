package com.reviewpilot.service.context;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContextSliceTest {
    @Test
    void capturesImmutableContextLines() {
        var lines = new ArrayList<>(List.of("1: code"));
        var slice = new ContextSlice("A.java", 1, 1, lines);
        lines.clear();
        assertAll(
                () -> assertEquals(List.of("1: code"), slice.lines()),
                () -> assertThrows(UnsupportedOperationException.class, () -> slice.lines().clear()));
    }
}
