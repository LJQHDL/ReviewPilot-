# ReviewPilot

> AI PR Review 助手 — 输入一个 GitHub PR URL，自动拉取变更、识别风险点、按文件类型生成有上下文的 Review 建议。

## 当前阶段

**V3 ReAct Agent 已完成**。LLM 从单次调用升级为 ReAct（Reasoning + Acting）自主循环——Agent 可以调用 `fetch_file_content` 和 `search_repo` 两个工具获取更多上下文，最多 8 轮、4 次工具调用后收敛。Critic Agent 对输出做 5 维质检（HALLUCINATION / MISSING / SEVERITY / DUPLICATE / CONSISTENCY），发现问题自动触发修订。9 条风险规则、前端 Vite 构建均可用。

测试：`mvn test` → 现有测试全部通过；`vite build` 成功。

## 目录结构

```
reviewpilot/
├── backend/            Spring Boot 3 服务（Java 17）
├── frontend/           Vue3 + Vite + Element Plus 极简前端
├── docs/
│   ├── architecture.md  Pipeline 架构图（ASCII / Mermaid）
│   ├── prompt-strategy.md  Prompt 设计与 Token 策略
│   └── requirements/   比赛题目与计划原始文档（参考）
├── .gitignore
└── README.md
```

## 系统架构

输入是一个 GitHub PR URL，后端按 7 个阶段预处理后进入 ReAct Agent 自主循环，Critic 质检通过后输出结构化 Review。前端只渲染结果。

```
HTTP POST /api/review { prUrl }
        │
        ▼
┌──────────────────────────────────────────────────────┐
│                  ReviewPipeline                       │
│                                                       │
│  1. GithubPrFetcher           GET /pulls/{n}/files   │
│  2. DiffParser                自实现 unified diff     │
│  3. FileClassifier            6 类启发式分类          │
│  4. RiskDetector              9 条规则，patch-only    │
│  5. ContextLoader             hunk ±3 行切片          │
│  6. FileContentFetcher        风险文件全量拉取         │
│                                                       │
│  ┌── ReAct Agent Loop (ReviewAgent) ────────────┐    │
│  │  System Prompt + Diff → LLM decides:          │    │
│  │    • fetch_file_content(path) 获取完整文件      │    │
│  │    • search_repo(query)       搜索仓库         │    │
│  │    • output final JSON                        │    │
│  │  Max 8 rounds, converges after 4 tool calls   │    │
│  └──────────────────────────────────────────────┘    │
│                                                       │
│  ┌── Reflection Loop (ReflectionOrchestrator) ───┐   │
│  │  CriticAgent 5-dim QA → issues? → Revision    │   │
│  │  HALLUCINATION / MISSING / SEVERITY /          │   │
│  │  DUPLICATE / CONSISTENCY                       │   │
│  └──────────────────────────────────────────────┘    │
│                                                       │
│  Risks 合并：rule + AI 按 (file,line,message) 去重    │
└──────────────────────────────────────────────────────┘
```

完整带 Mermaid 图与各阶段输入输出示例：见 [`docs/architecture.md`](docs/architecture.md)。
Prompt 模板与 Token 策略：见 [`docs/prompt-strategy.md`](docs/prompt-strategy.md)。

**核心工程亮点**：

- **ReAct Agent 自主循环**：LLM 不再是被动的一次性调用，而是主动决定"我需要看哪个文件的完整代码""我需要搜索哪些符号"，最多 8 轮自主探索后输出最终审查结果。
- **Critic 质检 + 修订**：Agent 输出经 Critic 做 5 维质量检查（幻觉/遗漏/严重度/重复/一致性），发现问题自动触发修订轮次。
- **规则 + AI 双层架构**：9 条确定性规则稳定命中已知模式（不依赖 LLM 心情），LLM 专注做规则抓不到的语义/架构层判断。规则按 `(file,line,message)` 去重后与 AI risks 合并，规则优先。
- **Tool Registry**：线程安全的工具调度器，`search_repo` 限流 25 次/分钟 + 缓存，`fetch_file_content` 通过 GitHub Contents API 拉取完整文件。

## 本地启动

### 0. 配置密钥（必看「密钥安全」章节）

```bash
# bash / zsh
export GITHUB_TOKEN=ghp_xxxxxxxxxxxxxxxxxxxxx        # 公开 PR 也建议配，5000 req/h vs 60
export DEEPSEEK_API_KEY=sk-xxxxxxxxxxxxxxxxxxx       # /api/review 必须

# PowerShell
$env:GITHUB_TOKEN     = "ghp_xxx"
$env:DEEPSEEK_API_KEY = "sk-xxx"
```

未配 `DEEPSEEK_API_KEY` 时后端依旧能启动，仅 `/api/review` 返回 401 + 明确错误，方便调试 GitHub 拉取层。

### 1. 后端

```bash
cd backend
mvn spring-boot:run
# 监听 http://localhost:8080
curl http://localhost:8080/api/health
```

### 2. 前端

```bash
cd frontend
npm install
npm run dev
# 浏览器打开 http://localhost:5173
```

dev 模式下前端通过 Vite 代理把 `/api/*` 转发到后端 `localhost:8080`。生产/演示场景前端 `dist` 起静态站点直连后端时，靠后端 `WebCorsConfig` 的 CORS 白名单（默认 `localhost:5173`）放行。

### 3. 端到端最小用例

打开浏览器 http://localhost:5173，输入：

```
https://github.com/octocat/Hello-World/pull/1
```

点 **Analyze**，几秒后看到三段：Summary（变更总结）/ Risks（彩色等级 + 文件:行号 + 描述）/ Suggestions（按文件分组）。

## 接口

### `GET /api/health`

存活检查。

### `GET /api/pr/files?prUrl=<github_pr_url>`

只跑「拉 PR + 解析 diff」，不调 LLM。给 PR#2 留的本地验证端点，也方便看 LLM 拿到的原始数据。

### `POST /api/review`

主端点。

```bash
curl -X POST http://localhost:8080/api/review \
  -H 'Content-Type: application/json' \
  -d '{"prUrl":"https://github.com/octocat/Hello-World/pull/1"}'
```

返回：

```json
{
  "prUrl": "...",
  "summary": "...",
  "risks":       [{"level":"HIGH|MEDIUM|LOW","file":"...","line":42,"message":"..."}],
  "suggestions": [{"file":"...","line":42,"message":"..."}],
  "meta": {"provider":"deepseek","model":"deepseek-chat","filesAnalyzed":3,"elapsedMs":4823,"agentRounds":1}
}
```

`risks` 同时包含规则命中（来自 RiskDetector）和模型识别（来自 DeepSeek），按 `(file, line, message)` 去重，规则结果优先。

错误码：`400` 非法 PR URL；`404` PR 私有/不存在；`401` GitHub 鉴权失败 或 DeepSeek key 未配置；`502` DeepSeek 调用本身失败。

## 密钥安全

**绝不要把 API key 提交进 git。** 本仓库已采取以下措施：

1. `application.yml` 只用 `${DEEPSEEK_API_KEY:}` / `${GITHUB_TOKEN:}` 占位，不写真实 key。`.gitignore` 也排除了 `.env` / `application-local.yml`。
2. `DeepSeekProperties.redactedKey()` 把 key 渲染成 `****<last4>`，是日志里**唯一**会出现的 key 形式（启动 banner、错误日志均走这个方法）。
3. `WebClientResponseException` 路径只记录 status + body 长度，不打印响应正文（响应体里偶尔会回显请求片段，可能含 token）。
4. 启动时 key 缺失**不抛异常**，仅 `/api/review` 真实调用时拒绝；既保护开发体验，也避免日志在启动期暴露异常栈带 key。
5. 前端永不接触 key —— 全部走后端代理。

如不慎泄露：立刻去 [DeepSeek 控制台](https://platform.deepseek.com/) 撤销该 key，再重新生成；GitHub token 在 [Settings/Developer settings/Personal access tokens](https://github.com/settings/tokens) 撤销。

## 开发路线

- ✅ PR#1 项目骨架
- ✅ PR#2 GitHub PR 抓取与 Diff 解析
- ✅ PR#3 接入 DeepSeek 完成 MVP 主链路
- ✅ PR#4 FileClassifier 文件类型识别
- ✅ PR#5 RiskDetector 规则风险检测（核心亮点）
- ✅ PR#6 ContextLoader + 分流 PromptBuilder（核心亮点）
- ✅ PR#7 Vue3 极简前端
- ✅ PR#8 README、架构图、Prompt 策略说明
- ✅ PR#9 Prompt/规则强化 + 演示打磨
- ✅ V3 ReAct Agent + Critic Reflection 循环

## 第三方依赖

| 模块 | 依赖 | 用途 |
|---|---|---|
| 后端 | Spring Boot 3.3.4 (`spring-boot-starter-web`, `spring-boot-starter-webflux`) | REST + WebClient |
| 后端 | Lombok（optional） | 样板代码 |
| 后端 (test) | `spring-boot-starter-test`（含 JUnit 5 + Mockito + MockMvc） | 单测主框架 |
| 后端 (test) | `okhttp3:mockwebserver 4.12.0` | GithubPrFetcher / DeepSeekProvider 真起 HTTP 服务的契约测试 |
| 前端 | Vue 3.5 | 框架 |
| 前端 | Vite 5 + `@vitejs/plugin-vue` | 构建 / 开发服务器 |
| 前端 | Element Plus 2.8 | UI 组件 |
| 前端 | Axios 1.7 | HTTP 客户端 |

外部服务：

- **GitHub REST API** v2022-11-28，调 `GET /repos/{owner}/{repo}/pulls/{n}/files`
- **DeepSeek Chat Completions API**（OpenAI 兼容），调 `POST /v1/chat/completions`，模型 `deepseek-chat`

## 原创范围声明

下列内容均为本仓库自实现，未从外部 AI Review / Codereview 项目复制：

| 模块 | 实现细节 |
|---|---|
| `DiffParser` | 自写 unified diff 解析器，行级标注 `oldLine/newLine/type`，未引入 `java-diff-utils` |
| `FileClassifier` | 6 类启发式分类（路径优先 → test 标记 → SQL 后缀 → Spring config 文件 → Java 注解扫描 → 文件名后缀回落），无外部规则库 |
| `RiskDetector` + 6 条规则 | SPI 接口 + 各自 `@Component`，patch-only 启发式，单条异常隔离不阻塞 |
| `ContextLoader` | 仅依赖 `DiffHunk` 自带 ±3 行 context，不调 GitHub raw API |
| `PromptTemplate` 6 条 guidance | 按文件类型手写的 review 关键词清单 |
| `PromptBuilder` 分组+预算 | 6k/file + 60k/total 双重护栏 + 显式截断标记 |
| `ReviewPipeline.parseModelReply` | 抗前导/尾随文字的 `extractJsonObject`；rule + AI risks 按 `(file,line,message)` 去重，rule 优先 |
| `ReviewAgent` | ReAct 循环：message 构建 → token 预算管理 → 收敛机制（4 次工具调用后停发工具） → parseWithRetry |
| `ToolRegistry` | 工具调度 + 限流（25 次/min 滑动窗口）+ 缓存 + 未知工具容错 |
| `ReflectionOrchestrator` + `CriticResult` | 5 维 Critic 质检（HALLUCINATION/MISSING/SEVERITY/DUPLICATE/CONSISTENCY）→ 解析失败重试 → 自动修订 |
| `JsonReplyCleaner` | 统一 JSON 清洗（fence 剥离 + extractJsonObject），ReviewAgent 和 ReflectionOrchestrator 共享 |
| `FileContentFetcher` | GitHub Contents API 完整文件拉取 + Base64 解码 + 8k 截断 |
| `ExceptionSwallowingRule` | catch X → throw new Y 类型洗白检测 |
| `HardcodedSecretRule` / `InsecureRandomRule` / `WeakHashRule` | 硬编码密钥 / 不安全随机数 / 弱哈希检测 |
| 前端 `ResultPanel/RiskList/SuggestionList` | 自写 Vue3 组件，仅依赖 Element Plus 标签/卡片基础组件 |

第三方服务（GitHub API / DeepSeek API）只用其公开 REST 接口；调用客户端、错误处理、重试与日志策略均自实现。

未复用任何旧个人项目代码。
