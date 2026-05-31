# ReviewPilot 架构图

> 配合 [README](../README.md#系统架构) 阅读。本文件展示 7 阶段 Pipeline 的数据流、各阶段输入输出、以及 rule + AI 风险合并策略。

## 整体数据流

```mermaid
flowchart TD
    UI[Vue3 前端<br/>输入 PR URL] -->|POST /api/review| C[ReviewController]
    C --> P[ReviewPipeline]

    subgraph PIPE[ReviewPipeline · 7 stages]
        F[1 GithubPrFetcher<br/>GET /pulls/{n}/files] --> D[2 DiffParser<br/>unified diff → DiffHunk]
        D --> CL[3 FileClassifier<br/>FileType 6 类]
        CL --> R[4 RiskDetector<br/>5 条规则 patch-only]
        CL --> CTX[5 ContextLoader<br/>hunk ±3 行切片]
        R --> PB[6 PromptBuilder<br/>按 FileType 分流模板]
        CTX --> PB
        PB --> M[7 ModelProvider<br/>DeepSeekProvider]
    end

    P --> PIPE
    M --> P
    P -->|合并 rule+AI risks 去重| C
    C -->|ReviewResult JSON| UI
```

## 各阶段输入输出

| # | Stage | 输入 | 输出 | 实现 PR |
|---|---|---|---|---|
| 1 | GithubPrFetcher | `PrUrl(owner, repo, number)` | `List<GithubPrFile>` | PR#2 |
| 2 | DiffParser | `String patch` | `List<DiffHunk>` 含 `oldLine/newLine/type` 行级标注 | PR#2 |
| 3 | FileClassifier | `GithubPrFile + 解析后 patch` | `FileType` ∈ {CONTROLLER, SERVICE, CONFIG, SQL, TEST, OTHER} | PR#4 |
| 4 | RiskDetector | `FileChange + FileType` | `List<RiskItem>` (规则命中) | PR#5 |
| 5 | ContextLoader | `FileChange + 命中行号` | `ContextSlice`（hunk 内已含 ±3 行原文） | PR#6 |
| 6 | PromptBuilder | files + classifications + risks + contexts | `String prompt`（按 FileType 分组 + 全局预算截断） | PR#3, 重写于 PR#6 |
| 7 | ModelProvider → DeepSeekProvider | prompt | `String reply`（含 JSON）| PR#3 |

后处理：`ReviewPipeline.parseModelReply` 容忍前导/尾随文字，提取 `{...}` 子串解析；rule + AI risks 按 `(file, line, message)` 去重，**rule 优先**——规则结果稳定可解释，LLM 结果做语义补充。

## 规则风险检测（RiskDetector）

6 条 `@Component` 规则，加规则只需丢一个 `@Component`，无中央注册表：

```
service/risk/
├── RiskRule.java                       SPI 接口
├── RiskDetector.java                   聚合器，注入 List<RiskRule>，单条 throw 不阻塞
└── rules/
    ├── UnreleasedLockRule.java         lock() 未配 finally{ unlock() }
    ├── NestedTransactionRule.java      @Transactional 方法内调本类另一个 @Transactional
    ├── BareCatchRule.java              catch (Exception) {} 空块（静默吞噬）
    ├── ExceptionSwallowingRule.java    catch X -> throw new Y 类型洗白（PR#9 加，HIGH/MEDIUM）
    ├── SqlConcatenationRule.java       String SQL = "..." + var + "..."
    └── SystemOutPrintlnRule.java       System.out.println 出现在生产代码
```

> 规则只扫 ADDED 行，不批改老代码；启发式 patch-only，不解析 AST（3 天预算内 AST 不划算）。

## Prompt 分流（PromptBuilder + PromptTemplate）

按 FileType 分组送给 LLM，每组前面挂一段 `PromptTemplate.guidance(FileType)`：

| FileType | guidance 关键词 |
|---|---|
| CONTROLLER | 入参校验、鉴权、错误码、Idempotency、统一返回 |
| SERVICE | 事务边界、并发安全、空指针、异常传播 |
| CONFIG | 默认值、敏感信息、Profile 隔离 |
| SQL | 索引、注入、显式列、N+1 |
| TEST | 断言强度、边界用例、Mock 滥用 |
| OTHER | 通用代码质量 |

详细 Prompt 设计与 Token 预算策略见 [`prompt-strategy.md`](prompt-strategy.md)。

## 失败路径

| 场景 | 状态码 | 来源 |
|---|---|---|
| 非法 PR URL | 400 | Controller `IllegalArgumentException` |
| PR 不存在或私有 | 404 | `GithubPrNotFoundException` |
| GitHub 401/403 | 401 | `GithubAuthException` |
| DeepSeek key 未配 | 401 | `AiProviderException`（消息以 "DeepSeek API key is not set" 开头）|
| DeepSeek 上游失败 | 502 | `AiProviderException`（其他）|

前端 axios 拦截器把后端 `{error: "..."}` 提取成 `Error.message`，UI 用 ElMessage + el-alert 双展示，避免新请求覆盖错误信息。
