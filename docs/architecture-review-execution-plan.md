# 架构审查整改执行计划（16 项 + 1 项死代码）

> ## ⚠ 2026-09-08 rebase 后的实际落地（读这里，别读下面的旧计划正文）
>
> `origin/main` 在这期间合入了 **PR#10 + `e04d927` 职责重构**，已经解决了本计划的一部分，
> 且用了**不同的类名与拆法**。因此本分支已 rebase 到 main 之上，并按"沿用 main 的结构、
> 只补 main 缺的行为"重新落地。与下文原计划的差异：
>
> | 原计划 | 实际 |
> |---|---|
> | #8 新建 `ReviewResultParser` | **复用 main 的 `ReviewReplyReader`**，只加 `readOrNull()`（修订解析失败时保留原审查）|
> | #7 新建 `AgentRun` | **复用 main 的 `AgentReview`**，加 token 两个字段 |
> | #5 自建 advice + `Exception` 兜底 | **复用 main 的 `ApiExceptionHandler`**，且**不引入 Exception 兜底**：兜底会把框架的 404/405/400/415 全吞成 500（这个坑我在 rebase 前真踩过并实测复现）。只补 `GithubApiException`→502、`RepoNotAllowed`→403，并把 401 的上游 body 改为只记日志 |
> | #12 `Tool`/`FileType` 移入 `model` + `RiskDetector` 收 Map | **未做**。属结构性改动，且 main 已有 `LayerBoundariesTest` 做字节码级边界校验，包级环不再是无约束风险。留作后续 |
> | #3 critic 看代码 | 落地在 main 的 `CriticPromptBuilder`/`CriticAgent` 上，不在 ReflectionOrchestrator 里自建 prompt |
>
> 已落地：#1 #2 #3 #4 #5(部分) #6 #7 #8(复用) #9 #10 #11 #13 #14 + 死代码清理。
> 未做：#12(结构)、#15 鉴权、#16 异步 —— 后两项的决定见文末 Gate 记录。
> 提交形态：**单个 commit**。原因：`ReviewPipeline` 同时引用 FetchedFiles / Deadline /
> RepoAllowlist / 新 Meta 字段，按主题切三个 commit 会让中间 commit 无法编译，
> 反而破坏 bisect。
> 验证：`mvn -o test` 185 全绿；真实 GitHub 调用验证分页、超时与响应视图。


依据：`docs/architecture-review-2026-09-08.md` §9
分支：`feat/v3-react-agent` ｜ 开始：2026-09-08
每阶段结束跑 `cd backend && mvn -q test` 作为门禁；**全部完成后由用户 review 再决定是否 commit**（不自动 push/merge）。

## 执行原则

1. 一项改动一次可验证 —— 每阶段末必须 `mvn test` 全绿，红则停下修，不带病进下一阶段。
2. 行为不变的改动（补 timeout、移类型、加 advice）优先做；改变对外契约的（VO、202 异步）排在后面且显式标注。
3. 涉及产品决策的两项（15 鉴权、16 异步）**不擅自决定**：做到那里会停下提问。
4. 不新增第三方依赖，除非该项明确需要（目前只有 #14 有界缓存可能需要，计划用 JDK 原生 `LinkedHashMap` 实现，不加 Caffeine）。
5. `CLAUDE.md` 里"已知粗糙处"章节的条目随修随更 —— 文档不能比代码更旧。

---

## Phase A — 生存性：低风险高收益的局部补丁（#1 #2 #4 #6 #5 + 死代码）

| 项 | 改动 | 主要文件 | 验证 |
|---|---|---|---|
| A1 (#1) | `githubWebClient` 补 connect timeout(10s) + `responseTimeout` + 4MB buffer；`GithubProperties` 加 `timeout`（默认 30s）；所有 `.block()` → `.block(Duration)` | `config/GithubClientConfig.java`、`config/GithubProperties.java`、`service/github/GithubPrFetcher.java`、`service/github/FileContentFetcher.java`、`application.yml` | 新增 `GithubClientConfigTest`（断言 connector 存在）；`mvn test` |
| A2 (#2) | yml `reviewpilot.ai.agent.*` → `reviewpilot.agent.*`，让四键真正生效；确认 `reflection.enabled=false` 可关 | `application.yml`（仅缩进/位置） | 新增或改一个 properties 绑定测试：设 false → `ReflectionOrchestrator` 返回 no-revision（现有测试已可覆盖） |
| A3 (#4) | `searchCode` 改用 `UriComponentsBuilder` 编码；拒绝含 `[#&{}]` 的 query（返回给 LLM 的字符串里说明原因，不破循环） | `service/github/GithubPrFetcher.java`、`service/ai/ToolRegistry.java` | 新增 `GithubPrFetcherTest` 用例：query 带 `#`/`&`/空格 时 URL 形状正确 |
| A4 (#6) | `fetchFiles` 检测 `files.size()==100` → 继续取下一页（分页循环，上限可配 `max-pages`，默认 3）；`ReviewResult` 暴露 `truncated`/`totalFiles` 到 `meta` | `service/github/GithubPrFetcher.java`、`model/ReviewResult.java`、`pipeline/ReviewPipeline.java` | 扩 `GithubPrFetcherTest`（两页 mock）+ `ReviewPipelineFlowTest` |
| A5 (#5) | 新建 `controller/ApiExceptionHandler`（`@RestControllerAdvice`）：迁入现有 4 类 handler + `GithubApiException`→502 + `DataBufferLimitException`/`RuntimeException`→500 统一 `{error}` 信封；删两个 Controller 内的 trio | `controller/*.java`、新文件 | `ReviewControllerErrorTest` 改造 + 新增 GithubApiException 用例 |
| A6 (死代码) | 删 `ReviewPipeline.parseModelReply` 及只喂它的测试断言 | `pipeline/ReviewPipeline.java`、`pipeline/ReviewPipelineParseTest.java` | `mvn test` |

**A 阶段风险**：低。唯一外部可见变化是 A4（meta 多两个字段，前端读不到的字段无害）与 A5（原本 500 裸 body 变 `{error}`，是修复不是破坏）。

- [x] A1
- [x] A2
- [x] A3
- [x] A4
- [x] A5
- [x] A6
- [x] `mvn -q test` 全绿

---

## Phase B — 质量闭环：让 critic 看到代码（#3）

| 项 | 改动 | 主要文件 |
|---|---|---|
| B1 (#3) | `refine(...)` 增加 `contexts`（或 diff 摘要）入参；`buildCriticPrompt` 渲染"可引用的代码上下文"段；critic system prompt 明确"你可以据此判定 HALLUCINATION，不能凭空推断" | `service/critic/ReflectionOrchestrator.java`、`pipeline/ReviewPipeline.java`、`service/prompt/PromptBuilder.java` |
| B2 (#8 的一部分) | 统一 revision 解析路径 → `keyFindings` 不再丢失（详见 C2，此处只保证不丢字段） | 同上 |

**验证**：`ReflectionOrchestratorTest` 断言 critic prompt 含代码上下文；人工跑一次真实 PR 看日志 `Critic found N issue(s)`。
**注意成本**：critic 输入变长 → token 上升（但仍是 `complete()` 单发调用，1 次）。若担心成本，可在 A2 之后用 `reflection.enabled=false` 关掉。

- [x] B1
- [x] `mvn -q test` 全绿（151 tests）

---

## Phase C — 结构固化：让边界成为结构而非文档（#7 #8 #11 #12）

| 项 | 改动 | 主要文件 | 风险 |
|---|---|---|---|
| C1 (#7) | 新增 `service/ai/AgentRun.java`（record：`review`+`rounds`+`toolCalls`）；`ReviewAgent.review()` 返回它并**删除两个可变字段**；`ReviewPipeline` 从返回值组装 `ReviewResult`（成为唯一构造点）；`ReviewResult` 不再被当半成品传递（agent 内部返回 null prUrl/meta 的 2 处消失） | `service/ai/ReviewAgent.java`、`service/ai/AgentRun.java`(新)、`pipeline/ReviewPipeline.java`、`service/ai/ReviewAgentTest.java` | Med |
| C2 (#8) | 抽 `service/ai/ReviewResultParser.java`（含 `keyFindings`），`ReviewAgent` 与 `ReviewPipeline` 各自 ~80 行重复解析全部删除，改为调用它 | 新文件 + `ReviewAgent.java`、`pipeline/ReviewPipeline.java` | Med |
| C3 (#11) | `ReviewPipeline`/`ReviewAgent`/`ReflectionOrchestrator` 复用 Spring 注入的 `ObjectMapper`（去掉 3 处 `new`）；`DsToolCall.parseArguments` 改用注入的 mapper 或 `JsonMapper` 静态实例；`AgentResponse.usage` 的真实 token 数写进 `ReviewResult.Meta`（`promptTokens/completionTokens`），并让 `estimateChars` 兜底而非首选 | `pipeline`、`service/ai/*`、`model/ReviewResult.java` | Low |
| C4 (#12) | 断包级环：`service.ai.Tool` → `model.Tool`（或 `service.ai` 之外的 `tool` 包）；`service.classifier.FileType` → `model.FileType`；`RiskDetector` 改为接收 `Map<String,FileType>` 不再注入 `FileClassifier`（消除双重分类 + 越层边） | 大量 import 变更 + `service/risk/RiskDetector.java`、`pipeline/ReviewPipeline.java` | Low（机械改动，编译器兜底） |
| C5 (验证) | 重跑包依赖脚本，确认 `prompt ⇄ ai` 环消失、`risk → classifier` 边消失 | — | — |

- [x] C1
- [x] C2
- [x] C3
- [x] C4
- [x] C5 依赖图复查
- [x] `mvn -q test` 全绿（155 tests，包循环数 0）

---

## Phase D — 弹性与契约（#10 #14 #13 #9）

| 项 | 改动 | 主要文件 | 风险 |
|---|---|---|---|
| D1 (#10) | `ReviewContext`（或参数对象）携带 `deadlineAtMillis`；`ReviewAgent` 每轮检查、`ReflectionOrchestrator` 调用前检查，超支则走已有 `forceComplete` 路径；同时把 `retry`/`timeout`/`max-rounds`/前端 120s 调成一组自洽的数（服务端最坏 < 客户端） | `service/ai/ReviewAgent.java`、`service/critic/ReflectionOrchestrator.java`、`pipeline/ReviewPipeline.java`、`application.yml`、`frontend/src/api/review.js` | Med |
| D2 (#14) | `ToolRegistry`：限流按 `owner/repo` 分桶（`ConcurrentHashMap<String,Bucket>` + 原子 `compareAndSet` 窗口）；`searchCache` 换成有界 LRU（JDK `LinkedHashMap` + `synchronizedMap` 或手写 accessOrder，上限可配） | `service/ai/ToolRegistry.java`、`application.yml` | Med |
| D3 (#13) | 新增 `controller/dto/PrFilesResponse.java`（`filename,status,additions,deletions,hunkCount`，`?include=patch` 时才带 patch）；同步改 `frontend` 消费点 | `controller/PrFilesController.java`、新 dto、前端调用处 | **破坏性**，需与前端同改 |
| D4 (#9) | 加 `reviewpilot.github.allowed-repo-patterns`（默认空 = 允许全部，本地行为不变）；非空时在 `PrUrl` 校验后拦截并给 403 语义；启动时若 token 非空且 allowlist 为空则 WARN 一行"服务可代表调用方读取任意私有仓库" | `config/GithubProperties.java`、`model/PrUrl.java` 或 `pipeline`、`application.yml` | Low-Med |
| D5 (P1-4 剩余) | `AiProviderException` 加 `reason` 枚举，替换 `ReviewController:69` 的 `startsWith("DeepSeek API key is not set")` 字符串嗅探 | `service/ai/AiProviderException.java`、`DeepSeekProvider.java`、advice | Low |
| D6 (P2-8) | 上游 raw body 不再回显客户端：`GithubAuthException` 详情进日志，响应给 generic message | `service/github/GithubAuthException.java`、advice | Low |

- [x] D1
- [x] D2
- [x] D3（含前端）
- [x] D4
- [x] D5
- [x] D6
- [x] `mvn test` 全绿（164 tests）+ 前端 `npm run build` 通过

---

## Phase E — 需要你先决策，不擅自实施（#15 #16）

**#15 鉴权**。三种形态代价差别很大，且改变"这产品能不能匿名用"的定位：
- E-a 固定 API key（`X-Api-Key` 比对环境变量）——最小可用，1 小时，适合"给自己/评委一个链接"。
- E-b GitHub OAuth 登录（用户自己授权，只读其可见仓库）——正确形态，但要接 OAuth 与 session，工作量 ~2-3 天，且会引入状态存储。
- E-c 保持匿名 + D4 的仓库 allowlist + 公开降权 token——不鉴权，但把 P0-1 的爆炸半径限死。

**#16 异步 job + 轮询**（`POST 202 + GET /api/reviews/{id}`）。这是 #10/#15 之外唯一真正的**架构形态变更**：需要任务状态存储（哪怕先用内存 + 单实例假设）、任务生命周期、前端进度 UI。我在报告 §8 明确建议**有真实并发用户之前不做**。要做的最小闭环版本大约 1-2 天。

→ 执行到 D 结束时停下，用 AskUserQuestion 确认 E 段范围。

- [x] E 决策（2026-09-08 用户确认：**#15 与 #16 均不做**）

**决定的内容与理由**
- #15 鉴权：保持 localhost 无鉴权。P0-1（confused deputy）的**缓解**已经由 D4 落地
  （`allowed-repos` + token 非空且 allowlist 为空时启动 WARN），所以风险是被记录且可关闭的，
  而不是被忽略的。**若将来部署到任何非 localhost 环境，本条必须先做。**
- #16 异步 job：不做。D1 之后服务端最坏情况已收敛到 request-budget(45s) + 一次在途调用
  (< 41s) < 前端 180s，单人/演示场景不需要任务生命周期与状态存储。
  触发条件：出现真实并发用户，或需要审查历史。

**未列入 16 项、仍然遗留的 P2/P3**（报告 §5/§6，都不紧急）：
每请求仍构建两次 user prompt（反思关闭时白烧一次）；`ToolCall.mapToJson` 不转义；
`fetchHeadRef` 每次重取；`finish_reason` 未读（无法区分截断与格式错）；提示词散落 3 处；
无 metrics/tracing/request-id；GitHub 403 仍并入 401 分支。

---

## 每阶段提交策略

不自动 commit。每阶段全绿后我把 diff 摘要给你；你说"可以提交"我再按仓库规范建分支/commit（commit message 英文、按文件显式 `git add`、不用 `-A`、时间戳用真实系统时间不回写）。若你要走 PR 流程，描述按 `## 做了什么 / ## 为什么 / ## 怎么验证` 三段式。

## 回滚

Phase A/B 每项都是局部文件改动，`git checkout -- <file>` 可单项撤销（但**当前工作区已有未提交改动**：`CLAUDE.md` 修改 + 若干未跟踪文件，动 `git checkout/restore` 前必须先 `git status` 核对，不盲目清理）。

---

# 审查通过 Gate（2026-09-08 /loop 复核）

独立复核（一个专职子代理按 diff 做对抗性审查）+ 运行时实测，结果是**发现了 4 个问题，
其中 2 个是我在整改过程中自己引入的回归**，全部已修：

| 级别 | 发现 | 状态 |
|---|---|---|
| P1 | `ApiExceptionHandler` 的 `@ExceptionHandler(Exception.class)` 抢在 Spring 自己的状态映射之前：未知路径 404、错误方法 405、缺参数 400、错误 Content-Type 415 **全部变成 500** | 已修（显式 handler + `ApiErrorContractTest` 6 例锁定）。运行时实测 4 个状态码全部正确 |
| P1 | `request-budget-ms` 并不约束总耗时，只约束"还能不能发起下一轮模型调用"——之前的 yml/CLAUDE.md 注释把它写成了一个它做不到的保证 | 已修（deadline 现在也挡在每个工具调用前；预取加了 `max-prefetch-files` 上限；`github.timeout` 30s→10s；注释改为如实描述）。**残余限制见下** |
| P2 | revision 解析失败时 `agentRounds` 仍报 2（声称发生了一次其实没发生的修订） | 已修 + 新增测试锁定"保留原审查且只报 1 轮" |
| P2 | `deepseek.timeout` 我压到 20s，4096 max-tokens 的正常输出可能超时变 502 | 已回调到 30s |

子代理判定为 clean 的部分：ToolRegistry 的分桶与 `synchronizedMap` + accessOrder LRU 并发推理、
两个 `@ConfigurationProperties` record 的绑定、脚本批量改动的 import/参数无错乱、
`aiReview`/`reviewed` 的使用一致性、分页 truncated 标志的三种边界。

## 最终状态

```
mvn -o test                    171 tests, 0 failures, 0 errors   （基线 147）
npm run build                  ✓
包循环依赖                      1 → 0（图遍历实测）
启动                            正常，allowed-repos 空/非空两条路径各验证一次
真实 GitHub 调用                /api/pr/files 返回 200，5 字段 VO，
                                patch 仅在 ?include=patch 时出现，内部 hunks 未外泄
```

## 仍然开着的问题（如实记录，不是"已完成"）

1. **P0-1 鉴权未做**（用户决定）。当前缓解 = `RepoAllowlist`（默认空=允许任意仓库）
   + token 非空且无 allowlist 时启动 WARN。**部署到非 localhost 之前这条必须先关。**
2. **P0-2 只部分缓解**。同步端点下不存在真正的耗时上界：一旦某次模型调用发出，
   它一定跑完自己的 `timeout × (max-retries+1)`。要让上界成立只有 #16 异步 job 一条路，
   已决定暂缓。前端 axios 180s 是兜底，不是对齐过的预算。
3. 报告 §5/§6 里未进 16 项的 P2/P3（双次 user prompt 构建、`ToolCall.mapToJson` 不转义、
   `fetchHeadRef` 重复请求、`finish_reason` 未读、prompt 散落 3 处、无 metrics/trace、
   GitHub 403 仍并入 401）——都还开着，都不紧急。
