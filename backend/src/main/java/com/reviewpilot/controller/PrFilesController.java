package com.reviewpilot.controller;

import com.reviewpilot.model.ErrorResponse;
import com.reviewpilot.model.PrUrl;
import com.reviewpilot.service.diff.FileChange;
import com.reviewpilot.service.github.GithubAuthException;
import com.reviewpilot.service.github.GithubPrFetcher;
import com.reviewpilot.service.github.GithubPrNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
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

    private final GithubPrFetcher fetcher;

    public PrFilesController(GithubPrFetcher fetcher) {
        this.fetcher = fetcher;
    }

    @GetMapping("/files")
    public List<FileChange> files(@RequestParam("prUrl") String prUrl) {
        PrUrl pr = PrUrl.parse(prUrl);
        return fetcher.fetchFiles(pr);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleBadInput(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(GithubPrNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(GithubPrNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(GithubAuthException.class)
    public ResponseEntity<ErrorResponse> handleAuth(GithubAuthException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ErrorResponse.of(e.getMessage()));
    }
}
