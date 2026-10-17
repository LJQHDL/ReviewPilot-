# ReviewPilot 质量修复 Plan（2026-10-17）

对应 spec：`docs/specs/2026-10-17-fix-batch/spec.md`

## 架构概览

本次修复不新增模块，只在既有边界内调整五处组件：

1. **Agent 预算闸门**（`ReviewAgent`）：新增"预算耗尽即停止"的出口，负责尽力解析最后一次回复或返回降级结果；不改变 `ReviewAgent.review(...)` 的签名。
2. **API 准入与关联**（`ApiSecurityFilter`、`ApiExceptionHandler`、`application.yml`）：过滤器增加 API 路径判断与审查提交判断；request ID 写入 MDC 和响应头；异常处理器日志携带 request ID。
3. **GitHub 抓取边界**（`GithubPrFetcher`、`PrUrl`）：异常按传输/编程错误分类；分页在整页边界多探一页；URL 拒绝 `.`/`..`。
4. **前端交互状态**（`RiskList.vue`、`App.vue`、新增 `utils/risk.js`）：展开状态改为内容键；组件卸载时清理计时器。
5. **仓库交付**（`.gitignore`、`docs/security-deployment.md`、Git 提交）：清理当前版本的调试产物，补齐忽略规则，按主题提交。

## 核心数据结构

**不新增**公开记录、API 字段或响应结构。

- `Deadline`：保持不变，继续用 `expired()` 作为唯一预算判断。
- `AgentReview`：字段不变（`result`、`reactRounds`、`toolCallCount`、token 统计）。
- `FetchedFiles`：字段不变，`truncated` 的判定逻辑变准确。
- 降级审查结果复用 `ReviewResult`：summary 写入"审查时间预算已用尽，以下为规则检测结果。"，风险/建议/发现列表为空；流水线随后合并规则风险。
- 新增前端纯函数：

```
riskKey(risk) -> string   // file|line|message，与后端 RiskMerger 去重键一致
```

## 核心接口

| 接口 | 变化 |
|---|---|
| `ReviewAgent.review(files, classifications, ruleRisks, contexts, prTitle, pr, deadline)` | 签名不变；预算耗尽行为改变 |
| `GithubPrFetcher.fetchFiles(PrUrl)` | 签名不变；`truncated` 语义修正 |
| `PrUrl.parse(String)` / 紧凑构造器 | 增加 `.`/`..` 拒绝 |
| `ApiSecurityFilter.doFilterInternal(...)` | 行为变化：只处理 API 路径，只对 `/api/review` 计审查额度 |
| `riskKey(risk)` | 新增导出函数 |
| HTTP 响应 | JSON 结构不变；`X-Request-ID` 响应头保留 |

## 模块设计

### Agent 预算闸门（`ReviewAgent`）

**职责：** 预算耗尽时停止一切模型调用。

- 新增私有方法 `stopAtBudget(messages, loop)`：
  1. 从后向前找最近一条 `role=assistant` 且 `content` 非空的回复；
  2. 用 `ReviewReplyReader.parse(...)` 直接解析（**不走** `read(...)` 的 repair 回调），捕获 `JsonProcessingException`；
  3. 解析成功 → 返回该 `ReviewResult`；失败 → 返回降级结果：summary 为"审查时间预算已用尽，以下为规则检测结果。"，risks/suggestions/keyFindings 为空。
- 替换两处 `forceComplete`：
  - 循环顶部发现过期：`return finish(stopAtBudget(messages, loop), loop);`
  - 工具调用期间过期：不再执行工具，本轮结束后由顶部检查进入 `stopAtBudget`。
- `forceComplete` 仅保留给"上下文字符超限"和"达到最大轮次"两条非预算路径。
- 日志：WARN 记录 `round`、`toolCalls`、以及是否成功复用了最后一条回复。

**依赖：** 既有 `ReviewReplyReader`、`ModelProvider`、`ToolRegistry`、`PromptBuilder`。

### API 准入与关联（`ApiSecurityFilter`、`ApiExceptionHandler`、`application.yml`）

**过滤器新流程：**

1. 计算 `path`；若不以 `/api/` 开头 → 直接 `chain.doFilter`，不做鉴权/配额/安全头。
2. 生成 `requestId`（UUID），写响应头 `X-Request-ID`，`MDC.put("requestId", ...)`，`try/finally` 中 `MDC.remove`。
3. 健康检查 GET、CORS 预检 → 放行。
4. 鉴权开启时校验 Bearer。
5. 仅当 `POST` 且 `path.equals("/api/review")` 时执行 `acquire/release`；其他 POST 不再消耗审查额度。

**日志关联：** `application.yml` 增加 `logging.pattern.level: "%5p [%X{requestId:-}]"`；`ApiExceptionHandler` 的 warn/error 日志显式带 `requestId`。

**依赖：** `ObjectMapper`、`ErrorResponse`、`MDC`。

### GitHub 抓取边界（`GithubPrFetcher`、`PrUrl`）

- 异常分类：保留 `WebClientResponseException` 状态码映射；对 `RuntimeException` 先判断是否传输故障（`WebClientRequestException`、`DataBufferLimitException`、`.block(Duration)` 的 `IllegalStateException: Timeout on blocking read`）→ 包装 `GithubApiException`；否则原样抛出（→ 500，由框架记 ERROR）。
- `fetchPrTitle` 使用同一判断：传输故障记录 WARN 并返回 null；编程错误继续抛出。
- 分页：正常翻页结束后，若最后一页恰好满 100 且已到 `max-file-pages`，额外探一页 `max-file-pages + 1`：空页 → `truncated=false`；非空 → `truncated=true`（探到的内容不加入结果）。
- `PrUrl` 紧凑构造器：owner 或 repo 为 `.` / `..` 时抛 `IllegalArgumentException`（→ 400）。

**依赖：** `WebClient`、`GithubProperties`、`DiffParser`。

### 前端交互状态（`RiskList.vue`、`App.vue`、`utils/risk.js`）

- 新增 `riskKey(risk)`：`file|line|message`。
- `RiskList` 的 `expanded`、`copied` 均改用 `riskKey` 作为键；`v-for :key` 同步改用内容键；`aria-controls` 继续用渲染下标。
- `App.vue` 引入 `onBeforeUnmount(stopTimer)`。
- 新增 `frontend/src/utils/risk.test.js`，使用 Node 内置 `node:test`，不新增依赖。

### 仓库交付

- `.gitignore` 增加：`.playwright-mcp/`、`.claude/settings.local.json`、`.codex/`、`.mewcode/`、根目录 4 个笔记、面试文档、根目录 4 张 PNG、`docs/security-access-mobile.png`。
- 当前版本移除已跟踪的 4 张 PNG 与 `.playwright-mcp/page-*.yml`（不改写历史）。
- 保留并提交：`AGENTS.md`、`.claude/agents/`、`backend/.claude/agents/`、`frontend/.claude/agents/`、`start.ps1`、`start-reviewpilot.bat`、安全部署文档与计划文档、设计报告。

## 模块交互

### 预算耗尽路径

```
ReviewPipeline.review
  └─ ReviewAgent.review
       ├─ 正常轮次：chat → 工具 → continue
       ├─ deadline.expired()（循环顶部或工具调用后）
       │    └─ stopAtBudget
       │         ├─ 找到最后一条 assistant 内容 → ReviewReplyReader.parse（无 repair）
       │         └─ 解析失败 → 降级 ReviewResult
       └─ AgentReview 返回
  └─ deadline.expired() → 跳过 ReflectionOrchestrator
  └─ RiskMerger.merge(ruleRisks, parsed.risks())  // 降级时保留规则风险
  └─ ReviewResult(summary=降级说明, risks=规则风险)
```

### API 请求路径

```
请求 → CorsFilter（预检直接返回）
     → ApiSecurityFilter
          ├─ 非 /api/* → 直接放行
          ├─ 设置 requestId/MDC/安全头
          ├─ /api/health 或预检 → 放行
          ├─ 鉴权失败 → 401
          ├─ POST /api/review → acquire → 业务 → finally release
          └─ 其他 → 放行
     → Controller → ApiExceptionHandler（日志带 requestId）
```

### 分页探测

```
for page in 1..maxFilePages:
    fetch(page)
    if empty or size < 100: complete = true; break
if not complete:
    probe = fetch(maxFilePages + 1)   // 仅探测，不加入结果
    complete = probe is empty
truncated = !complete
```

## 文件组织

```
docs/specs/2026-10-17-fix-batch/
├── spec.md
├── plan.md                     ← 本文件
├── task.md
└── checklist.md

backend/src/main/java/com/reviewpilot/
├── service/ai/ReviewAgent.java                 — 预算闸门
├── config/ApiSecurityFilter.java               — 路径/配额/MDC
├── controller/ApiExceptionHandler.java         — requestId 日志
├── service/github/GithubPrFetcher.java         — 异常分类 + 探测页
└── model/PrUrl.java                            — `.`/`..` 校验

backend/src/main/resources/application.yml      — requestId 日志格式

backend/src/test/java/com/reviewpilot/
├── service/ai/ReviewAgentDeadlineTest.java     — 硬停止断言
├── config/ApiAdmissionTest.java                — 非审查 POST 不占额度
├── service/github/GithubPrFetcherTest.java     — 探测页 + 异常分类
└── model/PrUrlTest.java                        — `.`/`..`

frontend/src/
├── utils/risk.js                               — 新增 riskKey
├── utils/risk.test.js                          — node --test
├── components/RiskList.vue                     — 内容键
└── App.vue                                     — 计时器清理

.gitignore                                      — 个人文件/产物忽略
docs/security-deployment.md                     — GET 限流边界措辞
```

## 技术决策

| 决策点 | 选择 | 理由 | 被否决的备选 |
|---|---|---|---|
| 预算耗尽解析 | 直接调用 `ReviewReplyReader.parse` | `read(...)` 失败时会触发 repair 模型调用，违反硬停止 | 复用 `read` + 空修复回调（仍会多打一次日志且语义模糊） |
| 过滤器作用域 | 在 `doFilterInternal` 内判断路径 | 不改注册方式，避免 Bean 双重注册与既有测试动荡 | 移除 `@Component`、改 `FilterRegistrationBean`（改动面大） |
| 分页探测 | 满载时多探一页 | 改动局限在 `fetchFiles`，不重构元数据获取 | 读取 PR 元数据 `changed_files`（多一次耦合，改动更大） |
| 异常分类 | `isTransportFailure(RuntimeException)` 辅助判断 | 既保留 502，又让编程错误以 500 暴露；能覆盖 `.block` 超时 | 仅 catch 具体异常类型（`block` 超时是 `IllegalStateException`，无法干净区分） |
| 日志关联 | 过滤器写 MDC + 日志 pattern 统一输出 | 所有日志自动带 requestId，无需逐处改调用 | 每个日志点手工传参（易漏） |
| 风险展开键 | `riskKey = file\|line\|message` | 与后端 `RiskMerger` 去重键一致，过滤切换稳定 | 数组下标（会错位） |
| Javadoc 清理 | 反向应用 `0eb1810` 的补丁 | 精确对应 64 条新增注释，可先 `--check` | 手写脚本扫描（作为冲突时的回退） |
| `.gitignore` | 精确路径/目录 | 避免 `*.png` 之类误伤正常资源 | 宽泛通配（会误伤） |
| 提交序列 | 基线 + 6 个主题提交 | 历史可审查、可回滚；用户已确认 | 单次大提交（不可回滚） |

## 风险与缓解

| 风险 | 缓解 |
|---|---|
| `git apply -R 0eb1810` 与工作区同文件改动冲突 | 先 `git apply --check -R`；冲突时改用按文件删除新增注释的脚本，并在提交前用 `git diff` 复核 |
| 探测页改变 MockWebServer 请求序列 | 同步更新 `GithubPrFetcherTest`：满载场景显式入队探测页；新增整页边界测试 |
| `.block(Duration)` 超时是 `IllegalStateException`，仅靠类型无法区分 | `isTransportFailure` 同时判断异常类型与 "Timeout on blocking read" 前缀；主路径仍由 WebClient 的 responseTimeout 覆盖 |
| MDC 随线程池复用泄漏 | `finally` 中 `MDC.remove`；仅在 API 路径内设置 |
| 前端内容键在完全相同的两条风险上冲突 | 后端 `RiskMerger` 已按同一键去重；冲突时表现为同步展开，可接受 |
| 基线提交误带入个人文件 | 提交前显式列出暂存文件并检查 `git status --short`；个人文件只留在忽略规则中 |
| 提交后某个中间提交无法编译 | 每个提交前运行受影响模块测试；最终提交后跑全量 `mvn -o test` |

## spec 覆盖

| spec 需求 | 实现位置 | 提交 |
|---|---|---|
| F1 删除调试残留 | `ReviewPipeline.java` | 2 |
| F2 清理失效 Javadoc | 41 个 Java 类 | 2 |
| F3 预算耗尽硬停止 | `ReviewAgent` | 3 |
| F4 准入作用域收敛 | `ApiSecurityFilter` | 4 |
| F5 个人文件策略 | `.gitignore` / 暂存清单 | 7 |
| F6 GitHub 异常分类 | `GithubPrFetcher` | 5 |
| F7 PR URL 段校验 | `PrUrl` | 5 |
| F8 可观测性恢复 | `ApiSecurityFilter`、`ApiExceptionHandler`、`application.yml` | 4 |
| F9 分页截断准确 | `GithubPrFetcher` | 5 |
| F10 前端展开状态 | `RiskList.vue`、`utils/risk.js` | 6 |
| F11 前端计时器清理 | `App.vue` | 6 |
| F12 文件查询准入边界 | `docs/security-deployment.md` | 7 |
| F13 调试产物清理 | `.gitignore` + `git rm` | 7 |
| F14 提交可审查 | Git 提交序列 | 全部 |
| N1–N8 | 见 spec，验收由 checklist 覆盖 | — |

## 提交序列

1. **基线提交**：现有鉴权/脱敏功能 + 测试 + 安全文档 + 启动脚本 + Agent 定义。
2. `docs: remove dead duplicate Javadoc`（F1/F2）
3. `fix(agent): stop model calls when request budget is exhausted`（F3）
4. `fix(security): scope admission to review submissions and correlate logs`（F4/F8）
5. `fix(github): classify failures, validate PR segments, and detect truncation accurately`（F6/F7/F9）
6. `fix(frontend): key risk expansion by content and clear timer on unmount`（F10/F11）
7. `chore(repo): remove debug artifacts, ignore local files, document GET boundary`（F12/F13/F5 忽略规则）

每个提交完成前运行受影响模块的测试；最终提交后运行全量 `mvn -o test` 与前端构建。

## 自检

1. **spec 覆盖**：F1–F14 均在实现位置与提交序列中有归属；无缺口。
2. **接口完整性**：仅新增私有方法与一个前端纯函数；原有公开签名不变。
3. **依赖清晰度**：各组件只依赖既有边界，无环。
4. **矛盾检查**：无新增依赖、无响应结构变化，与 N1/N4 一致。
