package com.reviewpilot.controller;

import com.reviewpilot.service.diff.FileChange;
import com.reviewpilot.pipeline.PrFilesQuery;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Thin demo endpoint exercised in PR#2 to verify the GitHub fetch + diff
 * parsing pipeline end-to-end. PR#3 introduces the proper /api/review
 * endpoint that adds AI analysis on top of these primitives.
 */
@RestController
@RequestMapping("/api/pr")
public class PrFilesController {

    private final PrFilesQuery query;

    public PrFilesController(PrFilesQuery query) {
        this.query = query;
    }

    @GetMapping("/files")
    public List<FileChange> files(@RequestParam("prUrl") String prUrl) {
        return query.files(prUrl);
    }

}
