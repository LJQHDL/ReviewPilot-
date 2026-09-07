# ReviewPilot 后端架构审查报告

日期：2026-09-08 ｜ 分支：`feat/v3-react-agent` ｜ 范围：`backend/`（Java 17 / Spring Boot 3）
依据能力：`comprehensive-review`（architect-review / security-auditor rubric）+ `backend-development`（backend-architect / skills:api-design-principles）
约束：项目既有规范优先；每条结论均有 `file:line` 证据；未修改任何代码。

**方法说明**：插件 agent 类型在 session 启动后安装，未热加载，因此按其磁盘 rubric 文件跑了两个专职子代理，并对每条结论回查代码验证。其中 `ToolCall.mapToJson` 与 SSRF 两条**修正/推翻**了子代理或 `CLAUDE.md` 的原有说法（见 P2-6、P3）。

---

## 0. 审查维度适用性

3,973 LOC / 56 文件 / **单 Maven 模块**（全仓仅 `./backend/pom.xml`），依赖只有 `web`、`webflux`、`lombok`。

| 维度 | 结论 | 证据 |
|---|---|---|
| 一 Maven 多模块 | **N/A** → 改包级边界审查（§7） | `find . -name pom.xml` = 1 |
| 二 分层调用链 | 适用，发现 1 处跨层 | `PrFilesController:28-38` |
| 三 DTO/VO/Entity | 适用（内部类型当对外契约） | `PrFilesController:34` 返回 `List<FileChange>` |
| 四 业务模块边界 | 适用（等价于 pipeline/service 边界） | §7 |
| 五 事务边界 | **N/A** — 无 DB；`@Transactional` 仅出现在启发式规则的字符串匹配 | `NestedTransactionRule:50` |
| 六 Redis | **N/A** → 等价物：无界内存缓存 | `ToolRegistry:31` |
| 七 MQ | **N/A** → 无异步/队列；等价风险：同步长调用 | P0-2 |
| 八 MyBatis/SQL | **N/A** → 等价物：外部 API 调用放大 / 静默截断 | P1-2、P2-7 |
| 九 API 架构 | 适用 | P0-1 / P1-4 / P1-7 |
| 十 安全 | 适用，**本项目最弱一环** | §3 |
| 十一 可维护性 | 适用 | §5、§6 |
| 十二 演进风险 | 适用 | §8、§9、§10 |

明确**未**套用：Outbox / CQRS / Event Sourcing / Saga（`backend-development` 均提供这些 skill）。理由：无持久化写、无第二消费者、无跨资源写事务 —— 套上即过度设计。

---

## 1. Executive Summary

1. **分层是真实的，不是目录式的**：`model` 包**零出边**（纯叶子），`pipeline` 是唯一宽依赖编排者，Controller 层无任何 AI 依赖注入 —— `CLAUDE.md` 声明与实际代码一致。
2. **测试覆盖打在正确位置**：26 个测试覆盖全部确定性阶段（9 条规则各有测试、DiffParser、Classifier、PromptBuilder、Provider），说明边界是为可测试性设计。
3. **安全模型是空的**：无 Spring Security、无限流、无鉴权，而服务持有**特权 `GITHUB_TOKEN`**，调用方可指定**任意 owner/repo** → confused deputy。唯一我认为阻塞对外部署的问题。
4. **两个外部依赖弹性待遇不一致**：`DeepSeekClientConfig` 有 connect/response timeout + 4MB buffer；`GithubClientConfig` **三者全无**，且所有 GitHub 调用都是无超时 `.block()`。审查当天在本机实测到 git 连 github.com:443 卡 21 秒 —— 该失败模式不是假设。
5. **一次请求可在服务端跑几十分钟，而客户端 120s 就放弃**：60s×3 重试×8 轮 ReAct + critic + revision vs `frontend/src/api/review.js:5` 的 120s；无取消传播 → 用户重试点一次 = 付两次钱。
6. **V2/V3 头号能力（反思闭环）结构上无法完成其声称的事**：critic 输入里**没有 diff**，`[HALLUCINATION]` 判定无 ground truth；revision 又是无工具单发重写且会丢 `keyFindings`。
7. **变更频率最高的资产（prompt 语料）被编译进 Java**：`PromptBuilder.systemPrompt()` 单方法 249 行；prompt 文本实际散落 3 个类，与 `CLAUDE.md` 的 "All prompt text" 冲突。改一句措辞需重新编译部署。
8. **配置层有可验证的失效**：`application.yml:38` 把 `agent:` 嵌在 `ai:` 下，代码读 `reviewpilot.agent.*` → 四个键**全部**走默认值，其中 `reflection.enabled: false` 关不掉反思。文档承诺与实际行为不一致，且失败无日志。
9. **并发正确性靠约定而非结构**：`ReviewAgent` 单例可变 `lastReactRounds/lastToolCallCount` 被 pipeline 回读；`ToolRegistry` 限流计数器 JVM 全局且 check-then-act 非原子。今天统计串味，**加第二实例那天就是真 bug**。
10. **总体评级：B（基本合理，有少量结构问题）**。骨架健康：无 Maven 循环、仅 1 个包级环、纯叶子 model、边界清晰可测。扣分在运维安全 posture（P0）、质量闭环逻辑断裂（P1）、以及一批"文档说 A 代码做 B"的漂移。若继续加业务/接私有仓库而不先补鉴权与 deadline，会直接滑到 C。

---

## 2. 当前架构

**形态：单模块同步流水线 + LLM Agent 环（弱化版 Hexagonal，无 DB）**

```
                ┌──────────────── model/ (纯叶子，零出边) ────────────────┐
                │ PrUrl  ReviewResult(+Meta)  RiskItem  RiskLevel         │
                │ Suggestion  ErrorResponse                                │
                └──────────────────────────────────────────────────────────┘
   HTTP                ↑ 被各层引用（方向正确）
    │
    ├─ POST /api/review ─► controller/ReviewController ─► pipeline/ReviewPipeline  ★唯一宽依赖编排者(9 deps)
    │                            (只注入 pipeline)            │
    │                                                        ├─► service/github/GithubPrFetcher
    │                                                        ├─► service/classifier/FileClassifier
    │                                                        ├─► service/risk/RiskDetector ──┐ 越层: risk→classifier
    │                                                        │     └─► 9× risk/rules/*       │
    │                                                        ├─► service/context/…          │
    │                                                        ├─► service/github/…           │
    │                                                        ├─► service/prompt/PromptBuilder ◄══╗ 包级环
    │                                                        ├─► service/critic/Reflection     ║
    │                                                        └─► service/ai/ReviewAgent ──────╝
    │                                  (回读可变字段) └─► service/ai/ToolRegistry ─► service/github/*
    └─ GET /api/pr/files ─► controller/PrFilesController ──► service/github/GithubPrFetcher
                            ★ 跨层：Controller 直取基础设施，并把内部类型 List<FileChange> 当响应契约
```

**调用链与数据流（★=审查中新发现的浪费/缺陷）**

```
prUrl → PrUrl.parse            [host allowlist ✓；owner/repo 无字符校验]
  → fetchFiles                 ★ per_page=100 无分页 → >100 文件静默截断
  → classify(每文件)            ★ 第 1 次分类
  → RiskDetector.scan          ★ 第 2 次分类（注入 classifier）
  → ContextLoader.load
  → fetchForRiskyFiles          批量预取全文（只给 critic/revision）
  → ReviewAgent.review          ReAct ≤8 轮；toolCallCount<4 才给工具
  │    ├ fetch_file_content     ★ 每次重取 fetchHeadRef（重复元数据请求）
  │    ├ search_repo            ★ 全局 25/60s + 永久无界缓存
  │    └ parseWithRetry         （读 keyFindings）
  → systemPrompt + build(full)  ★ agent 跑完后第 2 次构建 user prompt
  → ReflectionOrchestrator.refine  ★ critic 无 diff；revision 无工具单发
  → mergeRisks                  去重键 file|line|message（跨来源，措辞不同必漏判）
  ← ReviewResult                meta 从 ReviewAgent 可变字段回读 ★
```

**业务边界**：diff 解析 / 风险规则 / prompt 组装 / provider 调用 / agent 编排 / 反思，包划分与之一致，边界基本成立。真正的混乱点：`service.ai` 依赖 7 个包（最纠缠），以及"审查策略"（合并规则、预算判定、收敛条件、critic 判据）无归属 —— 散在 `ReviewPipeline`、`ReviewAgent`、`ReflectionOrchestrator` 三处。

---

## 3. P0 — Critical（阻塞对外部署；不阻塞本地迭代）

### P0-1 无鉴权 + 特权 token + 任意 owner/repo = 混淆代理，可读取私有仓库代码
- **位置**：`PrFilesController:34-38`、`ReviewController:43-49`、`GithubClientConfig:22-24`、`PrUrl:36-39`
- **证据**：`PrUrl` 只 allowlist **host**（`:37`），owner/repo 仅校验非空（`:17-19`）；`GithubClientConfig:22-24` 对**每个**出站请求无条件附加 `Bearer <token>`；全项目无 `@PreAuthorize`、无 Spring Security 依赖、无 filter。
- **为什么是问题**：服务代替调用方行使了调用方并不拥有的权限 —— 权限模型缺失，token 作用域决定爆炸半径。
- **实际风险**：token 带 `repo` 作用域且服务公网可达时，`GET /api/pr/files?prUrl=https://github.com/<org>/<私有库>/pull/1` 直接把私有 diff（含完整 `patch`）返给匿名调用方；`/api/review` 是同一路径的 LLM 摘要版。附带**私有仓库存在性探针**（404 vs 403，且上游 body 原样回显）。
- **推荐方案**：① 目标仓库/组织 allowlist（最贴合"给自己的项目用"）；② 服务 token 明确降权 public-only 并写入 README；③ 要求调用方自带 token 透传（真多用户形态）。外加 `/api/*` 鉴权 + 按调用方限流。
- **修改成本**：Low（①②十余行）／Medium（③含前端）

### P0-2 出站调用无 deadline：GitHub 客户端零 timeout，DeepSeek 阻塞重试，客户端 120s 已放弃
- **位置**：`GithubClientConfig:14-26`、`GithubPrFetcher:42,69,91`、`FileContentFetcher:66,100`、`DeepSeekClientConfig:19-21`、`DeepSeekProvider:135-171`（含 `:161` `Thread.sleep`）、`frontend/src/api/review.js:5`
- **证据**：`githubWebClient` 无 `clientConnector`/`responseTimeout`/connect timeout（对比 `deepSeekWebClient:19-21` 三件齐全，`:31` 还为"长 PR JSON >256KB"专门提升 buffer —— **同类修复只做了一半**）；5 个 GitHub 调用全部无超时 `.block()`；DeepSeek 60s×3 次尝试 + 退避，外层 `ReviewAgent:75` ≤8 轮，再加 critic（`:190,:199`）与 revision（`:86`）。
- **为什么是问题**：Servlet 线程被外部依赖无限期钉住；缺少**端到端**预算，每层只约束自己一次调用。
- **实际风险**：最坏 ~13 次模型调用 × 182s ≈ 40 分钟占用一个 worker（默认 maxThreads=200），现实长尾 4-6 分钟；客户端 120s 断开但阻塞链不感知取消 → 服务端跑完并烧完配额，重试即二次付费。GitHub 无 timeout ⇒ 一条被中间网络干扰挂住的连接（本机实测 21s）永久泄漏 worker，几十条即整站不可用；大 PR 另抛 `DataBufferLimitException`。
- **推荐方案**：① `githubWebClient` 补齐 timeout + buffer；② `.block()` → `.block(Duration)`；③ 引入贯穿 `ReviewAgent`/`ReflectionOrchestrator` 的**请求级 deadline**，超预算即"用已有材料出最终 JSON"；④ 使 客户端超时 > 服务端最坏情况 成立的一组参数。根本形态见 §8 异步化，不作 P0 要求。
- **修改成本**：Low（①②约 15 行）／Medium（③贯穿签名）

---

## 4. P1 — High

### P1-1 反思闭环拿不到 ground truth，且 revision 会降级输出
- **位置/证据**：`ReflectionOrchestrator:66-67`（`criticUser = buildCriticPrompt(ruleRisks, v0)`）vs `:86`（`userPrompt` 只给 revision）；`buildCriticPrompt:146-186` 只拼三张清单；critic system prompt 自陈"你只会收到 1.规则风险 2.AI 结果"（`:98-100`）→ **有意设计**，非漏传参。丢字段：`ReviewAgent:176` 读 `keyFindings`，`ReviewPipeline:211` 不读。
- **为什么是问题**：五类判定中 `[MISSING][DUPLICATE][SEVERITY][CONSISTENCY]` 是自参照的、无代码也能做（设计成立）；但 `[HALLUCINATION]` 本质是"结论是否被代码支持"，**无 diff 即不可能可靠判定**（只能靠"规则层没报"推断，而规则层只有 9 条启发式）。且 revision 是**无工具 `complete()` 单发重写**（`:86`，不重跑 `ReviewAgent`）：看不到代码的模型的意见，驱动了一次看不到工具的全量重写。
- **实际风险**：质量闭环在"防幻觉"卖点上净收益可能为负，且失败不可见（前端只见第二版）。
- **推荐方案**：把 `contexts`/diff 作为第三入参喂 `buildCriticPrompt`；**或**诚实收缩职责（删 `[HALLUCINATION]`，只保留 4 类，并在 javadoc/README 写明边界）。统一 revision 解析器使 `keyFindings` 不丢。
- **修改成本**：Low（传参）／Medium（给 critic 配只读工具做真验证）

### P1-2 变更文件 >100 的 PR 被静默截断，且 meta 谎报覆盖度
- **位置**：`GithubPrFetcher:39`（`/files?per_page=100`，单 GET 无分页循环，已 grep 确认）；`ReviewPipeline:158`（`filesAnalyzed = files.size()`）
- **为什么是问题**：为成本做截断可接受，**不可接受的是它不可见** —— `filesAnalyzed=100` 读起来像"整个 PR 看完了"。对代码审查产品这是可信度缺陷。
- **实际风险**：大 PR 得到看似完整的结论，用户不知漏了后半部分。
- **推荐方案**：加 page 循环（上限 100 页），或截断时显式返回 `truncated:true` + `totalFiles`，并在 prompt 注明"仅审查前 N 个文件"。
- **修改成本**：Low

### P1-3 四个 agent 配置键全部失效
- **证据**：`application.yml:38`（`agent:` 缩进在 `ai:` 下 = `reviewpilot.ai.agent.*`）vs `ReviewAgent:43,44`、`ReflectionOrchestrator:40`、`FileContentFetcher:34` 读 `reviewpilot.agent.*`。四键的 `@Value` 默认值恰好等于文档值，故"看起来正常"。
- **为什么是架构问题而非笔误**：使 `reflection.enabled=false` 这个**唯一的成本/延迟 kill switch 静默失效**，而 yml `:40-42` 注释仍承诺"设 false 即 V1 单次行为"。运维文档与行为不一致，失败无日志。
- **推荐方案**：yml 与代码统一（改缩进即可）；确立"启动日志打印生效值"约定（`ReflectionOrchestrator:44` 已在打 `enabled=`，把它变成正式做法）。
- **修改成本**：Low

### P1-4 错误映射覆盖不全：`GithubApiException` 无 handler
- **证据**：`GithubPrFetcher:51` 抛出；全项目 7 处 `@ExceptionHandler`（两个 Controller 各一套，无 `@ControllerAdvice`）均不含它 → GitHub 非 401/403/404 响应得到 **HTTP 500 + Spring 默认 body `{timestamp,status,error,path}`**，与自家 `{error}` 信封不一致。同理 `DataBufferLimitException`、JSON 解析失败。
- **为什么是问题**：错误契约只有"部分覆盖"，客户端无法稳定解析；两套 trio 是纯重复（信封本身一致 —— 我核查后否掉了"格式不同"的猜测）。
- **实际风险**：任何 GitHub 5xx/429 → 前端只能看到 `请求失败`。
- **推荐方案**：单个 `@RestControllerAdvice` 取代两套 trio + catch-all。
- **修改成本**：Low

### P1-5 限流器与缓存是 JVM 全局的，跨 PR 互相污染
- **证据**：`ToolRegistry:31`（`ConcurrentHashMap` 无界无淘汰，key 含 LLM 自由选择的 query）、`:32-33,98-104`（单 `AtomicInteger` + `volatile windowStart`，check-then-act 非原子）。类 javadoc `:19-22` 称 "Thread-safe" 只对 `PrUrl` 作为方法参数成立。
- **为什么是问题**：①一个 PR 的 25 次搜索会让**同时进行的其它 PR** 在剩余窗口内拿不到 `search_repo`（功能性正确问题）；②缓存 key 由 attacker/LLM 可控且永不淘汰 → 内存单调增长；③窗口重置存在竞态。
- **实际风险**：并发审查时 agent 能力随机退化；长期运行 OOM 增长。
- **推荐方案**：按 `owner/repo(+调用方)` 分桶限流 + 有界缓存（Caffeine 或 LinkedHashMap+maxSize）。
- **修改成本**：Medium

### P1-6 `search_repo` 查询串直接拼进 URI
- **证据**：`GithubPrFetcher:85-86` 字符串拼接 `query`（来自 LLM，`ToolRegistry:89` 不校验），无编码；`searchCode` 无测试覆盖。
- **为什么是问题**：`#` 之后不进服务端 → `q=foo#` **丢掉 `repo:` 作用域，用特权 token 做全局代码搜索**；`&` 可注入/覆盖参数；`{` 触发 URI 模板展开异常。输入源是 LLM，而 LLM 被攻击者可控的 PR diff 影响 —— 这是注入，不是风格。
- **实际风险**：越权范围的代码搜索；工具调用报错导致审查质量下降。
- **推荐方案**：`UriComponentsBuilder.queryParam(...)`（自动编码）或 `.uri(b -> ...)`，并拒 `[#&{}]`。
- **修改成本**：Low

### P1-7 单例可变状态回读 + 内部类型即对外契约
- **证据**：`ReviewAgent:52-56,113-114,119-120`（`@Service` 单例上的可变 `lastReactRounds/lastToolCallCount`）被 `ReviewPipeline:160` 回读；`PrFilesController:34` 返回 `List<FileChange>`（`service.diff` 脊柱对象，含 `patch`/`hunks`）。
- **为什么是问题**：前者在并发下把 A 请求的轮次写进 B 请求响应（今天只是数字错，**加第二实例即必然错**，并阻断横向扩展）；后者把 diff 层字段名变成公开契约，内部重命名即 breaking change，且响应体无界。
- **推荐方案**：`ReviewAgent.review()` 返回 `AgentRun{review,rounds,toolCalls}`，pipeline 从返回值取 —— 顺带消除 `ReviewResult` 被当半成品使用的问题（`ReviewAgent:156,177` 构造时 `prUrl=""`、`meta=null`，事后由 pipeline 重建）。`/api/pr/files` 换 VO（`filename,status,additions,deletions,hunkCount`，`patch` 由 `?include=` 控制）。
- **修改成本**：Medium／Low（VO 为破坏性变更，需与前端同步）

---

## 5. P2 — Medium

1. **两套近乎相同的 JSON 解析栈**：`ReviewPipeline:191-300` 与 `ReviewAgent:132-219` 各一份 `parseWithRetry/parseCleaned/parseRisks/parseSuggestions/extractField/parseLevel`（~80 行重复），差异仅在 `keyFindings` —— 正是 P1-1 丢字段的根因。→ 抽 `ReviewResultParser`。
2. **`ReviewPipeline.parseModelReply:191-200` 无 main 调用方**，仅被 `ReviewPipelineParseTest` 维持存活 —— **测试在喂养死代码**。删函数+删测试，或接回真实路径。
3. **prompt 所有权分散且与文档冲突**：`PromptBuilder` javadoc 称"all prompt text"，但 `ReviewAgent:84,106-107,121` 有硬编码中文控制串、`ReflectionOrchestrator:92-144` 有整个 critic 语料。对 LLM 应用 **prompt 即业务规则**，现在有三个所有者、改一处要重编译。→ 收进单一所有者（组件粒度上不必新建 `CriticPromptBuilder` bean，`ReflectionOrchestrator:26-28` 的"单一消费者"论证在 bean 层面成立）。
4. **每请求构建两次 user prompt 且内容不同**：`ReviewAgent` 走 `reactUserPrompt`（`Map.of()` 无全文），pipeline 在 agent **已跑完之后**（`:133-134`）再 build 带 `fullFileContents` 版本。后果：不反思时白烧一次构建（大 PR 为 MB 级拼接）；反思时 revision 看到的代码**多于**被审审查本身。
5. **真实 token 数被采集后丢弃，改用字符估算**：`DeepSeekProvider:88-94` 把 `usage` 装进 `AgentResponse`，**全项目无人读取**（已 grep）；同时 `ReviewAgent:222-229` 用 `estimateChars` 判预算、yml 注释用"/4≈token"。预算不准是自找的。成本 Low。
6. **`ToolCall.mapToJson:19-31` 不转义** —— **订正 `CLAUDE.md`**：外层 Jackson 会正确转义该字符串后再上线，故影响不是"请求 JSON 损坏/可伪造"，而是**回放到第 2+ 轮时 arguments 自身非法 JSON → 该工具调用作废/provider 报错**。触发条件真实存在（模型在 query 里给引号或换行），值得修（直接用 `ObjectMapper.writeValueAsString`），属 P2 可靠性问题而非安全洞。
7. **`fetchHeadRef` 每次调用打一次 PR 元数据**：`FileContentFetcher:81`（工具路径）+ `:42`（批处理）+ `ReviewPipeline:128` 的 `fetchPrTitle` 同一资源 → 一次审查最多重复 6+ 次。本项目版 N+1。成本 Low（请求内 memoize / 把 ref 放入解析后的上下文对象）。
8. **上游响应体原样回显客户端**：`GithubAuthException:7` 内嵌 GitHub raw body → `ReviewController:63`/`PrFilesController:52` 返回 `e.getMessage()`；`IllegalArgumentException` 路径同样回显原始输入（`PrUrl:33,38,50`）。与"detail 进日志、generic 给客户端"相反，并助长 P0-1 的存在性探测。
9. **`RiskDetector` 注入 `FileClassifier`（risk→classifier 越层）导致双重分类**：`RiskDetector:30,43` vs `ReviewPipeline:106-109`。改为 scan 时接收已算好的 `Map<String,FileType>` → 越层边与重复计算同时消失。成本 Low。
10. **状态码语义**：GitHub **403（rate limit）** 被并入 401 分支（`GithubPrFetcher:48-50`）→ 客户端拿到 401，无法区分"token 坏了"与"被限流"（应 429）。
11. **可观测性只有日志**：无 metrics、无 trace、无贯穿"13 次模型调用"的 request-id。对每次调用都花钱、延迟以分钟计的链路，RED 指标 + 关联 ID 不是企业仪式而是排障刚需。`/api/health` 为零依赖存活检查，对两个外部依赖无探活。

---

## 6. P3 — Low

- `new ObjectMapper()` 三处手建（`ReviewPipeline:66`、`ReviewAgent:34`、`ReflectionOrchestrator:43`）+ `DsToolCall.parseArguments:197` **每次工具调用新建一个**。
- 魔法数未与已配置阈值统一：`ReviewAgent:89,104`（`4` 出现两次）、`:238-241`（2000/1000）、`:79`（0.8）、`ToolRegistry:102`（25）、`FileContentFetcher:28`（8000）、`GithubPrFetcher:39`（per_page）。
- 提示词中英混杂：`ReviewAgent:84` 中文 vs `:106` 英文，critic 全英文。
- `DsChoice:180` 用 snake_case 字段 `finish_reason`（其余用 `@JsonProperty`），且从不读取 → **无法区分"输出被 max_tokens 截断"与"输出格式错"**，parse-retry 白烧一次调用。
- `ErrorResponse:15` javadoc 称用于 OpenAPI/Swagger 输出，但无 springdoc 依赖（文档腐化）。
- `GithubPrFile` 的 `blobUrl/rawUrl/contentsUrl/sha/changes` 永不读取。

---

## 7. 模块依赖问题

Maven 层**无循环**（单模块）。包层 1 个环 + 2 条越层边。

```
当前（边权 = import 数）：

  controller ──1──► pipeline ──► service.{ai, github, classifier, context, risk, prompt, diff, critic}
     │                             │
     │ 5        ┌── Tool(类型) ────┘
     ▼          ▼
  service.github   service.prompt ◄──1── service.ai      ★ 环
  service.diff     (model 无出边 ✓)      │
                                         └──► service.github (2)
  service.risk ──3──► service.classifier                 ★ 越层 + 重复分类
  service.risk.rules ──7──► service.classifier           （FileType 已是共享词汇）

推荐：

  controller ──► pipeline ──►（仅编排）──► service.* ──► model(叶子)
                                  │
  Tool / FileType ──► model       │  ← 环由"类型放错包"造成：移类型即可，不必移逻辑
  service.risk ◄─(接收 classifications Map)
```

- **环 `service.ai ⇄ service.prompt`**：`PromptBuilder:4` import `service.ai.Tool`；`ReviewAgent:11` import `PromptBuilder`。根因是跨边界 DTO `Tool` 住在 ai 包，被 `reactUserPrompt(..., List<Tool>)` 签名牵住。修法：移类型（`Tool` → `model/` 或独立包）。成本 Low。
- **`FileType` 已是事实领域词汇**（被 rules/prompt/ai/pipeline 共 20+ 边引用）却住在 `service.classifier`。与 `model/` 其他 record 同地位，应搬入 `model/`。
- **`controller ──5──► service.github`**：全项目唯一一处 Controller 绕过编排层直取基础设施（P1-7）。
- **正面**：`model` 零出边 ⇒ **领域层不反向依赖基础设施**（清单中"Domain 反向依赖 Controller/Infrastructure"一条干净）。`RiskRule` 经 `List<RiskRule>` 自动发现（`RiskDetector`），加规则零改动 —— 真·开闭原则落地，**保持原样**。
- **无边界强制**：无 ArchUnit（已 grep）。一致性全靠 `CLAUDE.md` 手写；上述 4 条违规恰好都是文档未覆盖处。

---

## 8. 推荐目标架构（渐进）

**保持不变（明确不要动）**：单 Maven 模块（56 类型/4k LOC 拆多模块纯属仪式）；`ReviewController` 只注入 pipeline；`model` 为纯叶子；`RiskRule` 自动发现；构造器注入 + record；26 个测试的分布。
**不要引入**：多模块、Controller/Service/Manager 三层套语、CQRS、Event Sourcing、Outbox、Saga、领域事件、DTO 转换器框架。

**四处接缝**：

```
① 一次审查 = 有返回值的纯过程（消除单例可变状态 + 半成品 ReviewResult）
   ReviewAgent.review(...) ─► AgentRun { ReviewResult review; int rounds; int toolCalls; }
   ReviewPipeline 从 AgentRun 组装 ReviewResult（唯一构造点）

② prompt 单一所有者 + 语料外置（措辞演进不触发重编译）
   service/prompt/ ← systemPrompt / reactSystemPrompt / criticPrompt / 控制串
   （可选）resources/prompts/*.st

③ 一次请求一个预算与一个身份
   ReviewContext { PrUrl pr; Deadline deadline; String requestId; Set<String> repoAllowlist; }
   └─ 贯穿 ReviewAgent / ReflectionOrchestrator / ToolRegistry（限流按此分桶）
   └─ requestId 进 MDC → 13 次模型调用可串联

④ 对外契约与内部模型解耦
   advice/ @RestControllerAdvice（取代两套 trio + GithubApiException + catch-all）
   controller/dto/ PrFilesResponse{filename,status,additions,deletions,hunkCount[,patch]}
   provider 边界：AiProviderException 带 reason enum（MISSING_API_KEY / UPSTREAM_FAILURE）
                  └─ 取代 ReviewController:69 的 startsWith("DeepSeek API key is not set")
```

**质量闭环落点**（对应 P1-1）：

```
现在: ReviewAgent(diff+工具) ─► critic(只看清单) ─► revision(单发重写,无工具)
A案(便宜)  critic 输入加回 contexts/diff，保留 5 类判定            ← 推荐先做
B案(诚实)   砍 [HALLUCINATION]，critic 只做 4 类自参照 + 文档写明边界
C案(以后)   给 critic 只读工具（复用 ToolRegistry），但需把 revision 也做成 ReAct
```

**若将来变多用户**（不是现在）：`POST /api/reviews` → `202 + {id}`，`GET /api/reviews/{id}` 轮询，审查在 `@Async`/队列执行。这才是 P0-2 的根本解法，但需状态存储 —— 有真实并发用户之前不做。

---

## 9. 重构优先级

| # | 改动 | 收益 | 风险 | 成本 |
|---|---|---|---|---|
| 1 | `githubWebClient` 补 connect/response timeout + buffer；`.block(Duration)` | High | Low | Low |
| 2 | yml `ai.agent` → `agent` 对齐四键（恢复 reflection kill switch） | High | None | Low |
| 3 | critic 输入加 diff/contexts（A 案） | High | Low | Low |
| 4 | `search_repo` 用 UriComponentsBuilder + 拒 `[#&{}]` | High | Low | Low |
| 5 | `@RestControllerAdvice` + 处理 `GithubApiException`/catch-all | Med | Low | Low |
| 6 | `files>100` 显式 `truncated` 或分页 | High | Low | Low |
| 7 | `AgentRun` 返回值取代单例可变字段 | Med | Low | Med |
| 8 | 抽 `ReviewResultParser`（消双栈，修 `keyFindings` 丢失） | Med | Med | Med |
| 9 | 仓库 allowlist / token 降权（对外部署前） | High | Med | Low-Med |
| 10 | 请求级 deadline 贯穿 + 重试/超时/客户端超时三数对齐 | High | Med | Med |
| 11 | 真实 `usage` token 数接进预算 + MDC requestId | Med | Low | Low |
| 12 | `Tool`/`FileType` 移入 `model`（断环）；`RiskDetector` 收 Map（断越层） | Low | Low | Low |
| 13 | `/api/pr/files` 换 VO（破坏性，需同步前端） | Med | Med | Low |
| 14 | per-PR 分桶限流 + 有界缓存 | Med | Med | Med |
| 15 | 鉴权 / 按调用方限流 | High | Med | Med |
| 16 | 异步 job + 轮询 | High | High | High |
| — | 删除 `parseModelReply` 死代码 + 其测试 | Low | Low | Low |

先做 **1-6**（局部、低风险、可单测验证，一次清掉"无 timeout、kill switch 失效、闭环无依据、注入面、裸 500、静默截断"）；**7-11** 让边界成为结构而非文档约定；**15 在部署到任何非 localhost 环境之前**；**16 在有真实并发用户之前**。

---

## 10. 最终结论

**是否适合继续迭代？** 适合，且底子偏好。单模块 + 纯叶子 `model` + 单一编排者 + 规则自动发现 + 打在确定性阶段上的测试，对 AI 工具类项目是恰当而非过度的结构。没有任何问题需要靠"重构成多模块/DDD 分层"解决。

**是否存在必须先解决的问题？** 分两种口径：
- 继续 localhost 自用/演示：**无阻塞项**；但 P1-3（kill switch 失效）与 P0-2 的 GitHub 无 timeout 建议今天就修（各十余行）。
- 部署公网或接私有仓库：**P0-1 与 P0-2 是硬阻塞**，其它一切优化排在其后。

**是否需要架构重构？** 不需要系统性重构。需要**局部重构 + 边界固化**：§9 的 1-12 项绝大多数是局部改动（配置对称、一个返回类型、一个 advice、一次类型搬家、一个 critic 入参），无一需要跨模块搬迁或改变调用链形态。判断依据：结构本身健康，问题集中在"外部依赖弹性"与"文档/注释与代码行为漂移"，两者均可小改动消除。

**局部还是系统性？** 局部。**并建议明确拒绝一次系统性重构**：本项目最有价值的是那 249 行审查规则语料与 9 条启发式的判断力，不是骨架；给 4k 行代码套分层收益为负。

---

## 附：刻意**未**列为架构问题的清单（风格/偏好）

Lombok 未使用与手写 getter 混用；rules 包内 `if/else` 链；注释语言混杂；变量命名；`service.ai` 10 个文件偏多；`ModelProvider` 仅一个实现 —— **该接口值得保留**：它同时是 `DeepSeekProviderTest` 的 mock 边界与 V4 换供应商的真实接缝，与"单实现即删接口"的教条相反；`RiskRule` 更有 9 个实现兑现了价值。**若**未来要加 `RiskRuleFactory`/`StrategyRegistry`/`PromptTemplateEngine`，那才是本项目滑向 C 的信号。
