# ReviewPilot 当前设计报告

> 用途：重启另一个仓库时的设计基线文档。  
> 范围：描述**当前仓库真实实现**（V3 ReAct Agent），不是愿望清单。  
> 日期：2026-10-17  
> 源仓库：`ReviewPilot`（Vue 3 + Spring Boot 3 同步单体）

---

## 1. 产品定位（一句话）

输入一个 **GitHub PR URL**，系统自动拉取变更 → 用确定性规则预标风险 → 用 ReAct Agent（LLM + 工具）做语义审查 → Critic 质检后可选修订 → 返回结构化 JSON（summary / risks / suggestions / keyFindings），前端按「决策优先」渲染。

**不是**：IDE 插件、CI Bot、向量知识库、多租户 SaaS。  
**是**：本地/小规模可部署的 AI PR Review 助手，强调「规则兜底 + LLM 语义增强」。

---

## 2. 技术栈一览

### 2.1 后端

| 项 | 选型 | 说明 |
|---|---|---|
| 语言 | Java 17 | record / switch / text blocks 均在用 |
| 框架 | Spring Boot 3.3.4 | 单 Maven 模块，无多模块拆分 |
| Web | `spring-boot-starter-web` | Servlet 同步阻塞；Controller → Pipeline |
| HTTP 客户端 | `spring-boot-starter-webflux`（WebClient） | **仅作出站 HTTP**；调用处 `.block()`，非端到端响应式 |
| JSON | Jackson（Boot 自带） | 审查结果 / 错误体契约 |
| 简化样板 | Lombok（optional） | 使用克制 |
| 测试 | JUnit 5 + Mockito + MockWebServer | 确定性阶段与 HTTP 契约；约 160+ 测试 |
| 持久化 | **无** | 无 DB / DAO / Redis / MQ |
| 模型厂商 | DeepSeek（OpenAI 兼容 `/v1/chat/completions`） | 经 `ModelProvider` 端口抽象 |
| 代码托管 | GitHub REST API | PR files / contents / code search |

### 2.2 前端

| 项 | 选型 | 说明 |
|---|---|---|
| 框架 | Vue 3.5（Composition API + `<script setup>`） | 单页，无 Vue Router / Pinia |
| 构建 | Vite 5 | dev 代理 `/api` → `:8080` |
| HTTP | Axios | 超时 120s；Bearer 访问令牌仅存内存 |
| UI 库 | **无 Element Plus**（README 过时） | 自研组件 + `styles/base.css` |
| 状态 | 组件内 `ref` + `localStorage` 历史（最多 5 条） | Decision-First：Verdict → KeyFindings → Risks |

### 2.3 运维与密钥

| 变量 | 作用 |
|---|---|
| `GITHUB_TOKEN` | 可选；提高 GitHub 限流（60 → 5000/h） |
| `DEEPSEEK_API_KEY` | `/api/review` 必需；缺则启动可成功、调用时 401 |
| `REVIEWPILOT_SECURITY_ENABLED` / `ACCESS_TOKEN` | 生产鉴权 |
| `REVIEWPILOT_ALLOWED_REPOS` | 仓库白名单（防 confused deputy） |
| `SPRING_PROFILES_ACTIVE=prod` | 强制安全配置齐全否则启动失败 |

默认监听 `127.0.0.1:8080`。无容器编排、无 K8s、无观测平台（仅 SLF4J 日志）。

### 2.4 刻意不做的技术选择

以下在当前阶段被明确否决或未引入，重启时勿默认带回：

- 数据库 / ORM / 向量库 / 长期 Memory  
- Redis 分布式缓存 / 分布式限流  
- 消息队列 / 异步审查 Job（当前全同步长请求）  
- 严格六边形 / 多 Maven 模块 / CQRS  
- 真实 tokenizer 库（用「字符数 / 4」估 token）  
- AST / Tree-sitter 级静态分析（规则是 patch 启发式）

---

## 3. 核心设计思想

### 3.1 混合智能：规则做确定性，LLM 做语义

```
死板模式匹配（裸 catch、SQL 拼接、硬编码密钥…）
    → RiskRule（0 token、可测、可解释）

跨文件 / 行为变化 / 架构层判断
    → LLM（ReAct + 工具补上下文）

最终输出
    → RiskMerger：规则优先，AI 补充，按 (file, line, message) 去重
```

**原则**：LLM 不擅长的事不要让 LLM 干。系统不是「调 LLM 的壳子」，而是 **规则层兜底 + LLM 语义增强**。

### 3.2 确定性预处理 + 自主 Agent 循环

流水线前半段是**纯确定性、可单测**的：

1. 拉 PR → 解析 unified diff → 文件分类 → 9 条风险规则 → 上下文切片  
2. 再进入 `ReviewAgent`：LLM 自己决定是否 `fetch_file_content` / `search_repo`，直到输出最终 JSON  

边界：`max-rounds=8`，工具约 4 次后收敛；另有整请求墙钟预算（默认 45s）控制「还能不能再开一轮」。

### 3.3 Prompt 是一等公民契约

- System prompt：**Schema 内嵌 + JSON-only + 空数组合法**（抑制幻觉）  
- User prompt：按 `FileType` 分组 + 每组 role guidance + 预标风险 + hunk 上下文 + patch  
- Token 预算多层护栏：单文件 patch 上限 / 总字符上限 / 会话字符上限 / 模型 max-tokens  
- 截断必须**显式标记**，对人和模型都可见  

详见既有文档 `docs/prompt-strategy.md`。

### 3.4 质量闭环：Critic → 一次修订

`ReflectionOrchestrator`：

1. CriticAgent 五维检查：`HALLUCINATION / MISSING / SEVERITY / DUPLICATE / CONSISTENCY`  
2. 有问题 → 注入反馈后 `complete()` 再出一版  
3. 修订解析失败 → **保留原评审**，绝不拿坏 JSON 顶替好结果  
4. 可用配置关闭（低延迟日常模式）

注意：当前 Critic **不读源码、工具不可用**，幻觉判定的 ground truth 有限——这是已知结构性弱点（见 §8）。

### 3.5 分层单体，务实边界

```
Controller（HTTP / 状态码）
  → Pipeline / Query（用例编排）
    → 确定性逻辑（classifier / risk / context / merger）
    → Agent / Reflection（LLM 循环）
    → Port: ModelProvider
    → Adapter: GitHub* / DeepSeekProvider / OpenAiMessageMapper
model/ 纯叶子，零出边
```

- **没有** DAO；不需要为了「看起来像企业架构」硬加仓库层  
- 编排层不解析 JSON、不拼厂商协议；解析归 `ReviewReplyReader`，协议归 `OpenAiMessageMapper`  
- Bean **无请求级可变状态**（`ReviewAgent` 统计放进 `AgentReview` 随调用返回）

### 3.6 安全：最小暴露面

- 服务持有特权 `GITHUB_TOKEN` → 必须有 **仓库白名单**，否则任意 caller 指定 owner/repo = confused deputy  
- 生产：Bearer 访问令牌 + 限流（默认 10/min）+ 并发上限（默认 2）+ 错误脱敏  
- 工具路径拒绝 `..` / 绝对路径 / query string  
- 前端访问令牌**只存内存**，不写 localStorage  

### 3.7 前端：Decision-First

渲染顺序刻意不是「先 dump 列表」：

1. **VerdictBanner**（PASS / WARN / FAIL，由 HIGH/MEDIUM 计数派生）  
2. **KeyFindings**  
3. 可筛选的 Risks + Suggestions  
4. Meta（模型、轮次、token、耗时）

诚实计时器：只显示已等待秒数，不编造虚假进度阶段。

---

## 4. 端到端数据流

```
POST /api/review { prUrl }
        │
        ▼
PrUrl.parse + RepoAllowlist
        │
        ▼
GithubPrFetcher ──► DiffParser ──► List<FileChange>
        │
        ▼
FileClassifier（6 类）+ RiskDetector（9 规则）+ ContextLoader（风险附近 ±N 行）
        │
        ▼（可选）RiskFileContextLoader 预取风险文件全文（上限 N 个，各截断）
        │
        ▼
ReviewAgent（ReAct）
  messages = [system, user(diff+rules+tools)]
  loop:
    chat(tools?) → tool_calls? → ToolRegistry.execute → 回填
                 → 无 tool → ReviewReplyReader → ReviewResult
  约束: maxRounds / TOOL_CALL_LIMIT / 字符预算压缩 / Deadline
        │
        ▼
ReflectionOrchestrator.refine（可关 / 预算耗尽则跳过）
        │
        ▼
RiskMerger.merge(ruleRisks, aiRisks)
        │
        ▼
ReviewResult + Meta → JSON → Vue 渲染
```

### API 表面（保持简单）

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/api/health` | 存活 |
| GET | `/api/pr/files?prUrl=` | 只拉 diff，不调 LLM（调试用） |
| POST | `/api/review` | 完整审查 |

错误语义大致：`400` URL 非法 / `401` 鉴权或密钥 / `404` PR 不存在或私有 / `429` 限流 / `502` 上游模型失败。

### 输出契约（稳定 Schema）

```json
{
  "prUrl": "...",
  "summary": "...",
  "keyFindings": ["..."],
  "risks": [
    { "level": "HIGH|MEDIUM|LOW", "file": "...", "line": 42, "message": "..." }
  ],
  "suggestions": [
    { "file": "...", "line": 42, "message": "..." }
  ],
  "meta": {
    "provider": "deepseek",
    "model": "deepseek-chat",
    "filesAnalyzed": 3,
    "elapsedMs": 4823,
    "agentRounds": 1,
    "reactRounds": 3,
    "reactToolCalls": 2,
    "promptTokens": 12000,
    "completionTokens": 800,
    "filesTruncated": false
  }
}
```

重启新仓库时：**这份 JSON 契约值得尽量兼容**，便于前端/演示脚本迁移。

---

## 5. 子系统说明书（重启时按模块迁移）

### 5.1 Diff 与变更模型

- 自实现 unified diff 解析（`DiffParser`），维护新旧行号  
- `FileChange` → `DiffHunk` → `DiffLine`（ADDED / DELETED / CONTEXT）  
- **规则只扫 ADDED 行**：避免把存量代码问题算到 PR 作者头上  

### 5.2 文件分类（6 类）

`CONTROLLER / SERVICE / CONFIG / SQL / TEST / OTHER`  
启发式：路径 + 注解 + 文件名。分类结果驱动：

- 哪些规则 `appliesTo`  
- Prompt 分组与 role guidance  

### 5.3 风险规则（9 条，可插拔）

接口 `RiskRule`：`id()` / `appliesTo(FileType)` / `scan(FileChange)`。  
新增规则 = 在 `service/risk/rules/` 丢一个 `@Component`，**无中心注册表**。

| 规则 | 意图 |
|---|---|
| UnreleasedLock | 加锁未见释放 |
| NestedTransaction | 事务注解 + 自调用 |
| BareCatch | 宽泛 catch 无日志/重抛 |
| ExceptionSwallowing | catch X → throw Y，cause 丢失/语义重映射 |
| SqlConcatenation | SQL 字符串拼接 |
| HardcodedSecret | 硬编码敏感值 |
| InsecureRandom | `java.util.Random` 等 |
| WeakHash | MD5/SHA1 等弱哈希 |
| SystemOutPrintln | 非测试代码里的 stdout |

规则约定：畸形输入不抛；`RiskDetector` 单条失败跳过并记日志。

### 5.4 上下文策略（三档，成本递增）

1. **免费**：hunk 自带 ±3 行（GitHub unified diff 默认）  
2. **便宜**：`ContextLoader` 按风险点合并窗口切片，**不发网络**  
3. **贵**：`RiskFileContextLoader` / 工具 `fetch_file_content` 拉全文；预取有 `max-prefetch-files` 上限  

设计取舍：全文进 prompt 极易打爆预算；默认让 Agent **按需**拉文件。

### 5.5 ReAct Agent

- 状态机在 `ReviewAgent`；统计在调用局部 `Loop` → `AgentReview`  
- 工具经 `ToolRegistry`：失败归一化为文本回给模型（不抛穿）  
- `search_repo` 策略在 `RepositorySearchService`（进程内限流 + LRU 式缓存；失败不缓存）  
- JSON 恢复统一 `ReviewReplyReader`：清洗围栏 → 提取 `{...}` → 失败反馈模型重试一次 → 再失败则摘要降级  

### 5.6 Reflection

- `CriticPromptBuilder` 只产文案  
- `CriticAgent` 只跑质检 + 解析重试  
- `ReflectionOrchestrator` 只编排「要不要改、改完用不用」  

### 5.7 Prompt 资产

当前 prompt **编译进 Java**（`PromptBuilder` / `PromptTemplate` / `CriticPromptBuilder`）。  
优点：与代码同测、类型安全。  
缺点：改措辞要重新编译部署——**重启新仓库时最值得外置的资产之一**。

System prompt 已沉淀的护栏（PR#9）：

- Behavior-change checklist（异常改动四问）  
- Severity rubric（行为变化 → HIGH）  
- NPE 需结合 context 的 null 守卫  
- Cause-inference fragility（别从异常类型反推语义）  

### 5.8 安全与准入

- `ApiSecurityFilter`：Bearer、限流、并发门闩  
- `ProductionSecurityConfig`：prod profile 缺配置 fail-fast  
- `RepoAllowlist`：`owner/repo` 或 `owner/*`  
- `ApiExceptionHandler`：按异常类型映射状态码；上游错误脱敏  

---

## 6. 配置与预算（行为开关）

关键默认值（`application.yml`）：

| 键 | 默认 | 含义 |
|---|---|---|
| `prompt.budget.max-patch-chars-per-file` | 6000 | 单文件 patch 上限 |
| `prompt.budget.max-total-chars` | 60000 | 用户材料总上限 |
| `agent.request-budget-ms` | 45000 | 整请求墙钟；到期不再**启动**新模型/工具轮 |
| `agent.reflection.enabled` | true | Critic/修订开关 |
| `agent.content-fetcher.max-prefetch-files` | 5 | 风险文件预取上限 |
| `agent.tools.search-limit-per-minute` | 25 | 按仓库搜索限流 |
| `agent.react.max-rounds` | 8 | ReAct 最大轮次 |
| `agent.react.max-total-chars` | 64000 | 会话字符预算 |
| `ai.deepseek.max-tokens` | 4096 | 输出上限 |
| `ai.deepseek.temperature` | 0.2 | 偏确定性 |
| `security.reviews-per-minute` | 10 | POST 审查频率 |
| `security.max-concurrent-reviews` | 2 | 并发审查上限 |
| `github.max-file-pages` | 3 | PR files 分页上限（超则 truncated） |

注意历史坑：`agent.*` 必须挂在 `reviewpilot` 下，**不能**嵌进 `ai:`，否则 `@Value` 静默走默认值。

---

## 7. 目录心智模型（新仓库可复用的分包）

```
backend/
  controller/     # HTTP 边界 + ExceptionHandler + DTO
  pipeline/       # 用例编排（ReviewPipeline, PrFilesQuery）
  model/          # 纯数据契约（零出边）
  config/         # WebClient、CORS、安全、Properties
  service/
    github/       # 外部适配（Fetcher / Searcher / Paths / Allowlist）
    diff/         # Diff 解析与模型
    classifier/   # FileType
    risk/         # Detector + Merger + RiskRule + rules/*
    context/      # 切片与可选全文预取策略
    prompt/       # Prompt 组装（或外置后改为 loader）
    ai/           # Agent、Provider、Tools、ReplyReader
    critic/       # Reflection 闭环
frontend/
  api/            # axios + URL 校验 + token
  components/     # Verdict / Risks / Suggestions / Meta…
  App.vue         # 页面状态机
```

---

## 8. 已知局限与债务（重启时优先决策）

下列问题在现仓已有审查记录；**新仓库不要无意识地原样搬过去**。

| 优先级 | 问题 | 影响 |
|---|---|---|
| P0 | 同步长请求 vs 前端 120s 超时；无取消传播 | 用户重试 = 双倍费用；线程被 `.block()` 占满 |
| P0 | 持有 GitHub token 时必须白名单 | 否则任意 PR URL 可代理读私仓 |
| P1 | Critic 无 diff/源码，幻觉检查弱 | 「质检闭环」名不副实的部分 |
| P1 | 全文按 head **分支名**而非固定 commit SHA | 审查中途 push 会漂 |
| P1 | Prompt 编译进 Java | 迭代成本高 |
| P2 | 规则是启发式 patch-only，无 AST | lambda / AOP 事务等场景漏报 |
| P2 | Token 用字符估算 | 中英混合偏差可达约 30% |
| P2 | 单实例内存限流/缓存 | 多实例需网关或外置存储 |
| P2 | Deadline 只阻止「新启动」，不中断已发出的模型请求 | 预算语义偏软 |
| P3 | README 仍写 Element Plus | 文档漂移 |

架构审查原文：`docs/architecture-review-2026-09-08.md`（部分条目后续已修，以上表以**当前仍成立**的风险为主）。

---

## 9. 重启新仓库时的继承建议

### 9.1 强烈建议继承（产品 DNA）

1. **规则 + AI 双层**，规则优先合并  
2. **确定性预处理可单测**，LLM 只接在编排末端  
3. **ReAct 工具补上下文**，而不是默认塞全文  
4. **多层字符/时间预算 + 显式截断标记**  
5. **JSON Schema 契约 + ReplyReader 自愈（一次）**  
6. **Decision-First 前端**（Verdict 先于细节）  
7. **仓库白名单 + 生产 fail-fast 安全配置**  
8. **`RiskRule` 插拔式发现**（加规则零注册表）  

### 9.2 建议改写 / 重做（不要照搬实现）

| 主题 | 建议方向 |
|---|---|
| 请求模型 | 同步 → **异步 Job + 轮询/SSE**（或至少可取消 + 更长前端等待策略） |
| Prompt | 外置 Markdown/YAML + 版本号；Java/TS 只负责组装与预算 |
| Critic | 必须带上 diff 或关键切片；修订轮也可开工具 |
| Git 钉扎 | 一律用 PR **head SHA**，不用浮动分支名 |
| 模型端口 | 保留 `ModelProvider`；厂商实现可换（OpenAI / Claude / 本地） |
| 规则引擎 | 可从「正则扫 patch」演进到可选 Tree-sitter / 语言插件，但仍保持插拔接口 |
| 前端 | 可继续 Vue；若重做可考虑更清晰的状态机与流式展示工具调用过程 |
| 观测 | 结构化日志 + 单次审查 traceId + token/费用面板（现有 Meta 已是雏形） |

### 9.3 明确不要带进新仓的复杂度

- 多 Agent 辩论 / Planner 图谱 / 向量长期记忆（当前规模 ROI 低）  
- Redis / MQ / 微服务拆分（除非要做真正的多租户异步 SaaS）  
- 「企业级」DAO 空壳  

MVP 判据仍适用：**先让一次 PR 审查又准又快又安全，再谈平台化。**

---

## 10. 验证基线（旧仓怎么证明自己）

```bash
# 后端
cd backend && mvn clean test

# 前端
cd frontend && npm install && npm run build
# API 辅助：node --test src/api/review.test.js
```

本地最小路径：配 `DEEPSEEK_API_KEY`（+ 建议 `GITHUB_TOKEN`）→ `mvn spring-boot:run` + `npm run dev` → 对公开 PR 调 `POST /api/review`。

---

## 11. 一页纸总览（给新仓库 README 用）

```
ReviewPilot = 确定性预处理 + ReAct(LLM+Tools) + Critic修订 + 规则优先合并

栈: Java17/SpringBoot3 (同步单体, WebClient.block) + Vue3/Vite + DeepSeek + GitHub API
无: DB / Redis / MQ / 向量库

思想:
  1. 规则抓模式，LLM 抓语义
  2. Agent 按需拉上下文，不默认塞全文
  3. Prompt 与预算是产品，不是附属配置
  4. 分层单体：编排 / 策略 / 适配 分离
  5. 安全默认：白名单 + 令牌 + 限流

重启优先改: 异步化、Prompt外置、Critic接地、SHA钉扎
```

---

## 附录 A：核心类速查

| 类 | 角色 |
|---|---|
| `ReviewPipeline` | 唯一宽依赖用例编排 |
| `ReviewAgent` | ReAct 状态机 |
| `ToolRegistry` | 工具定义与分发 |
| `RepositorySearchService` | 搜索限流与缓存策略 |
| `ReflectionOrchestrator` | Critic → 修订 |
| `ReviewReplyReader` | JSON 恢复与降级 |
| `PromptBuilder` / `PromptTemplate` | 审查材料与角色指导 |
| `RiskDetector` / `RiskMerger` / `RiskRule*` | 规则层 |
| `GithubPrFetcher` / `FileContentFetcher` / `GithubCodeSearcher` | GitHub 适配 |
| `DeepSeekProvider` / `ModelProvider` / `OpenAiMessageMapper` | 模型端口与协议 |
| `ApiSecurityFilter` / `RepoAllowlist` | 准入与仓库范围 |

## 附录 B：相关旧文档

- `docs/architecture.md` — 流水线与依赖图  
- `docs/prompt-strategy.md` — Prompt / Token 策略  
- `docs/java-layering-review.md` — 分层与逐类职责  
- `docs/security-deployment.md` — 生产安全部署  
- `docs/architecture-review-2026-09-08.md` — 结构性问题审查（含已修与未修项）  

---

*本报告描述的是源仓库「当前实现真相」，供另起炉灶时做取舍；不是要求新仓库逐行复刻。*
