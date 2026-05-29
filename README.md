# ReviewPilot

> AI PR Review 助手 — 输入一个 GitHub PR URL，自动拉取变更、识别风险点、按文件类型生成有上下文的 Review 建议。

当前阶段：**PR#3 接入 DeepSeek 完成 MVP 主链路（Day1 完成）**。后端能调 GitHub API 拿到 PR 全部变更，调 DeepSeek 拿到结构化 Review 结果。前端骨架页可调通后端健康检查，结果展示页将在 PR#7 完成。

---

## 目录结构

```
reviewpilot/
├── backend/            Spring Boot 3 服务（Java 17）
├── frontend/           Vue3 + Vite + Element Plus 极简前端
├── docs/
│   └── requirements/   比赛题目与计划原始文档（参考）
├── .gitignore
└── README.md
```

## 后端架构（PR#3 已落地的部分）

```
HTTP /api/review { prUrl }
        │
        ▼
ReviewController
        │
        ▼
ReviewPipeline ───────── orchestrator
   ├── GithubPrFetcher   (调 GitHub /pulls/{n}/files，per_page=100)
   ├── DiffParser        (自实现 unified diff，行级标注 oldLine/newLine)
   ├── PromptBuilder     (PR#6 升级为分文件类型模板)
   └── ModelProvider     (抽象，可换 Claude/OpenAI/Ollama)
            └── DeepSeekProvider (本期实现，OpenAI-compatible /v1/chat/completions)
```

PR#4-#6 会在 fetch 与 prompt 之间插入 FileClassifier / RiskDetector / ContextLoader。

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

前端通过 Vite 代理把 `/api/*` 转发到后端 `localhost:8080`，无需额外 CORS 配置。

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
  "meta": {"provider":"deepseek","filesAnalyzed":3,"elapsedMs":4823}
}
```

错误码：`400` 非法 PR URL；`404` PR 私有/不存在；`401` GitHub 鉴权失败 或 DeepSeek key 未配置；`502` DeepSeek 调用本身失败。

## 密钥安全

**绝不要把 API key 提交进 git。** 本仓库已采取以下措施：

1. `application.yml` 只用 `${DEEPSEEK_API_KEY:}` / `${GITHUB_TOKEN:}` 占位，不写真实 key。`.gitignore` 也排除了 `.env` / `application-local.yml`。
2. `DeepSeekProperties.redactedKey()` 把 key 渲染成 `****<last4>`，是日志里**唯一**会出现的 key 形式（启动 banner、错误日志均走这个方法）。
3. `WebClientResponseException` 路径只记录 status + body 长度，不打印响应正文（响应体里偶尔会回显请求片段，可能含 token）。
4. 启动时 key 缺失**不抛异常**，仅 `/api/review` 真实调用时拒绝；既保护开发体验，也避免日志在启动期暴露异常栈带 key。
5. 前端永不接触 key —— 全部走后端代理。

如不慎泄露：立刻去 [DeepSeek 控制台](https://platform.deepseek.com/) 撤销该 key，再重新生成；GitHub token 在 [Settings/Developer settings/Personal access tokens](https://github.com/settings/tokens) 撤销。

## 后续路线

- ✅ PR#1 项目骨架
- ✅ PR#2 GitHub PR 抓取与 Diff 解析
- ✅ PR#3 接入 DeepSeek 完成 MVP 主链路
- PR#4 FileClassifier 文件类型识别
- PR#5 RiskDetector 规则风险检测（核心亮点）
- PR#6 ContextLoader + 分流 PromptBuilder（核心亮点）
- PR#7 Vue3 极简前端结果页
- PR#8 README、架构图、Prompt 策略说明
- PR#9 演示打磨与 bugfix

## 第三方依赖

| 模块 | 依赖 | 用途 |
|---|---|---|
| 后端 | Spring Boot 3.3.4 (`spring-boot-starter-web`, `spring-boot-starter-webflux`) | REST + WebClient |
| 后端 | Lombok | 样板代码 |
| 后端 (test) | `okhttp3:mockwebserver 4.12.0` | GithubPrFetcher / DeepSeekProvider 真起 HTTP 服务的契约测试 |
| 前端 | Vue 3.5 | 框架 |
| 前端 | Vite 5 + `@vitejs/plugin-vue` | 构建 / 开发服务器 |
| 前端 | Element Plus 2.8 | UI 组件 |
| 前端 | Axios 1.7 | HTTP 客户端 |

外部服务：

- **GitHub REST API** v2022-11-28，调 `GET /repos/{owner}/{repo}/pulls/{n}/files`
- **DeepSeek Chat Completions API**（OpenAI 兼容），调 `POST /v1/chat/completions`，模型 `deepseek-chat`
