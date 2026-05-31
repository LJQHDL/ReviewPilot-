# ReviewPilot

> AI PR Review 助手 — 输入一个 GitHub PR URL，自动拉取变更、识别风险点、按文件类型生成有上下文的 Review 建议。

## 当前阶段

**Day 3 收尾（PR#9 进行中）**。后端 7 阶段 Pipeline 完整可用，前端可输入 PR URL → 加载 → 渲染 Summary / Risks / Suggestions / Meta。规则风险检测 6 条 + AI risks 合并去重；Prompt 含语义/异常 checklist + HIGH/MEDIUM/LOW 评级标尺；预算阈值可走配置而非硬编码。

测试：`mvn test` → 109/109 通过；`vite build` 成功。

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

输入是一个 GitHub PR URL，后端按 7 个阶段处理后调一次 LLM，输出结构化 Review。前端只渲染结果。

```
HTTP POST /api/review { prUrl }
        │
        ▼
┌─────────────────────────────────────┐
│         ReviewPipeline              │
│                                     │
│  1. GithubPrFetcher                 │  GET /pulls/{n}/files
│  2. DiffParser                      │  自实现 unified diff，行级 oldLine/newLine
│  3. FileClassifier   (PR#4)         │  CONTROLLER/SERVICE/CONFIG/SQL/TEST/OTHER
│  4. RiskDetector     (PR#5, +PR#9)  │  6 条规则，纯启发式 patch-only，仅扫 ADDED 行
│  5. ContextLoader    (PR#6)         │  hunk ±3 行已含上下文，按命中行切片
│  6. PromptBuilder    (PR#6, +PR#9)  │  按 FileType 分流模板 + 可配置 Token 预算 + 语义/异常 checklist + 评级标尺
│  7. ModelProvider                   │  抽象层，本期实现 DeepSeekProvider
│                                     │
│  Risks 合并：rule + AI 按 (file,line,message) 去重，rule 优先
└─────────────────────────────────────┘
```

完整带 Mermaid 图与各阶段输入输出示例：见 [`docs/architecture.md`](docs/architecture.md)。
Prompt 模板与 Token 策略：见 [`docs/prompt-strategy.md`](docs/prompt-strategy.md)。

**核心工程亮点**：不一次性把整团 diff 扔给 LLM，而是先做规则风险检测 → 上下文增强 → 按文件类型分流 Prompt → AI 分析。这让规则层稳定命中已知模式（不依赖 LLM 心情），LLM 专注做规则抓不到的语义/架构层判断。

**PR#9 强化**：针对评测反馈"能找代码层 bug 但抓不住异常语义改变 / 应在根因层修复 / 偶尔会报 NPE 假阳性"等资深 Reviewer 视角问题，做了五项强化：① 在 Prompt 里加"语义/异常/契约改变 checklist"和"HIGH/MEDIUM/LOW 评级标尺"，明确要求行为/语义被改变 → HIGH；② 新增 `ExceptionSwallowingRule` 检测 `catch X → throw new Y` 的类型洗白；③ Prompt Token 预算从硬编码改为 `reviewpilot.prompt.budget.*` 配置项，演示长 PR 时不需重编；④ NPE 标注前先扫 Context 块的 null 守卫，避免假阳性；⑤ Cause-inference fragility 守卫，让模型质疑"凭什么这个异常类型一定对应作者期望的那一个原因"，引导根因层修复建议。

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
  "meta": {"provider":"deepseek","model":"deepseek-chat","filesAnalyzed":3,"elapsedMs":4823}
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
- 🚧 PR#9 Prompt/规则强化 + 演示打磨（本 PR）

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
| 前端 `ResultPanel/RiskList/SuggestionList` | 自写 Vue3 组件，仅依赖 Element Plus 标签/卡片基础组件 |

第三方服务（GitHub API / DeepSeek API）只用其公开 REST 接口；调用客户端、错误处理、重试与日志策略均自实现。

未复用任何旧个人项目代码。
