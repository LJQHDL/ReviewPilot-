# ReviewPilot

> AI PR Review 助手 — 输入一个 GitHub PR URL，自动拉取变更、识别风险点、按文件类型生成有上下文的 Review 建议。

当前阶段：**PR#1 项目骨架（Day1）**。后端 Spring Boot 服务可启动，前端 Vue3 页面可启动并连通后端健康检查。AI 能力将在 PR#2 / PR#3 引入。

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

## 本地启动

### 后端

```bash
cd backend
mvn spring-boot:run
# 监听 http://localhost:8080
curl http://localhost:8080/api/health
```

### 前端

```bash
cd frontend
npm install
npm run dev
# 浏览器打开 http://localhost:5173
```

前端通过 Vite 代理把 `/api/*` 转发到后端 `localhost:8080`，无需额外 CORS 配置。

## 后续路线

- PR#2：解析 GitHub PR URL，调用 GitHub API 拿到变更文件与 diff，并实现 unified diff parser。
- PR#3：接入 DeepSeek API（`ModelProvider` 抽象层），跑通 PR URL → Review 结果的 MVP 链路。
- PR#4-6：FileClassifier / RiskDetector / ContextLoader + 分流 PromptBuilder 等工程亮点。
- PR#7-9：前端结果展示页 / README + 架构图 / 演示打磨。

## 第三方依赖（持续更新）

| 模块 | 依赖 | 用途 |
|---|---|---|
| 后端 | Spring Boot 3.3.4 (`spring-boot-starter-web`, `spring-boot-starter-webflux`) | REST + WebClient |
| 后端 | Lombok | 样板代码 |
| 前端 | Vue 3.5 | 框架 |
| 前端 | Vite 5 + `@vitejs/plugin-vue` | 构建/开发服务器 |
| 前端 | Element Plus 2.8 | UI 组件 |
| 前端 | Axios 1.7 | HTTP 客户端 |
