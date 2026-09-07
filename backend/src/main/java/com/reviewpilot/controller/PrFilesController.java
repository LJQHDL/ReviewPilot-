package com.reviewpilot.controller;

import com.reviewpilot.controller.dto.PrFilesResponse;
import com.reviewpilot.pipeline.PrFilesQuery;
import com.reviewpilot.service.github.FetchedFiles;
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
    public PrFilesResponse files(@RequestParam("prUrl") String prUrl,
                                 @RequestParam(name = "include", required = false) String include) {
        boolean withPatch = "patch".equals(include);
        FetchedFiles fetched = query.files(prUrl);
        List<PrFilesResponse.FileEntry> entries = fetched.files().stream()
                .map(f -> PrFilesResponse.FileEntry.of(f, withPatch))
                .toList();
        return new PrFilesResponse(entries, fetched.truncated());
    }
}
