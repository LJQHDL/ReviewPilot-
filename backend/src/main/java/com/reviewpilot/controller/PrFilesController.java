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
 * 提供 GET /api/pr/files 接口：仅执行 GitHub 拉取 + diff 解析（不调用 LLM），
 * 用于调试与验证预处理链路；完整 AI 评审请使用 POST /api/review。
 */
@RestController
@RequestMapping("/api/pr")
public class PrFilesController {

    private final PrFilesQuery query;

    public PrFilesController(PrFilesQuery query) {
        this.query = query;
    }

    /** include=patch 时在每个文件条目中附带原始 unified diff。 */
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
