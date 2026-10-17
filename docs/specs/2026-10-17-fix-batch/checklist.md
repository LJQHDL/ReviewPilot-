# ReviewPilot 质量修复 Checklist（2026-10-17）

> 每一项通过运行代码或观察行为来验证，聚焦系统行为。

## 实现完整性

- [ ] F1 调试残留已删除：`grep -rn "UserService.java" backend/src/main/java` 无结果，后端编译通过。
- [ ] F2 失效 Javadoc 已删除：扫描 64 个受影响文件，不存在"注解行后紧跟 `/**`"的模式；每类仍保留一份原有职责说明。
- [ ] F3 预算耗尽硬停止：过期预算下 `ModelProvider.chat` 调用次数不增加（单元测试断言）。
- [ ] F3 尽力解析：最后一次 assistant 回复为合法 JSON 时被采用（单元测试）。
- [ ] F3 降级结果：无法解析时 summary 含"预算耗尽"，规则风险仍出现在最终结果（单元测试 + 流水线集成测试）。
- [ ] F4 准入作用域：非 `/api/*` 路径不触发鉴权；只有 `POST /api/review` 消耗审查额度（过滤器测试）。
- [ ] F5 个人文件策略：`AGENTS.md` 与 `.claude/agents/` 被跟踪；其余个人文件在 `git status` 中不出现（`git check-ignore`）。
- [ ] F6 异常分类：传输故障仍为 502；模拟编程错误返回 500 且日志为 ERROR（单元测试 + MockMvc）。
- [ ] F7 URL 加固：owner/repo 为 `.`/`..` 时返回 400，且无 GitHub 出站请求（单元测试）。
- [ ] F8 可观测性：API 响应带 `X-Request-ID`；同一请求的日志含相同 ID；5xx 日志不含上游响应体/密钥（MockMvc + 日志捕获）。
- [ ] F9 截断准确：整页倍数的完整 PR 返回 `truncated=false`；超过页数上限返回 `truncated=true`（单元测试）。
- [ ] F10 展开状态：切换风险级别过滤后，展开状态仍对应原风险（前端单元测试 + 浏览器观察）。
- [ ] F11 计时器：组件卸载后计时器停止更新（前端单元测试/浏览器观察）。
- [ ] F12 GET 边界：部署文档明确 GET 不限流、由网关限流；鉴权开启时无令牌 401、有令牌正常（文档 + MockMvc）。
- [ ] F13 产物清理：`git ls-files '*.png'` 不含根目录 4 张；`git ls-files '.playwright-mcp/*'` 为空；对应路径被忽略。
- [ ] F14 提交序列：`git log --oneline` 显示基线提交 + 按主题拆分提交，每个提交可独立编译。

## 集成

- [ ] `ReviewPipeline` 收到降级结果后仍调用 `RiskMerger`，规则风险不丢失（流水线集成测试）。
- [ ] `ApiSecurityFilter` → Controller → `ApiExceptionHandler` 的 requestId 一致（MockMvc + 日志捕获）。
- [ ] `GithubPrFetcher` 的探测页内容不进入最终文件列表（分页单元测试）。
- [ ] `RiskList` 使用 `riskKey` 作为展开/复制状态键，构建通过且单测通过。
- [ ] 所有公开接口至少被一个真实调用方使用（编译 + 全量测试通过）。

## 编译与测试

- [ ] `cd backend && mvn -o test` 通过，测试数 ≥202，0 failure / 0 error / 0 skipped（记录命令输出）。
- [ ] `cd frontend && node --test src/utils/risk.test.js src/api/review.test.js` 通过（记录输出）。
- [ ] `cd frontend && npm run build` 通过（记录输出）。
- [ ] `git diff --check` 无空白错误。
- [ ] `pom.xml` 与 `package.json` 无新增依赖（`git diff` 确认）。

## 端到端场景

- [ ] **场景 1：预算耗尽降级**
  - 操作：用极短预算和 mock 模型跑一次审查，模型先返回工具调用并耗尽预算。
  - 预期：不再发起第二次模型调用；若最后回复是合法 JSON 则采用，否则 summary 含"预算耗尽"；最终响应仍包含规则风险。
- [ ] **场景 2：鉴权与配额**
  - 操作：开启鉴权，分别发送无令牌、合法令牌、连续两次审查、GET 文件查询。
  - 预期：无令牌 401；第一次审查 200；第二次 429 且有 `Retry-After`；GET 不受审查配额影响；所有 API 响应带 `X-Request-ID`。
- [ ] **场景 3：GitHub 分页边界**
  - 操作：mock 200 个文件（上限 2 页）与 300 个文件（上限 2 页）。
  - 预期：前者 `truncated=false`，后者 `truncated=true`；请求序列包含一次探测页。
- [ ] **场景 4：前端过滤与卸载**
  - 操作：展开一条 HIGH 风险，切换到 MEDIUM 再切回；评审进行中离开页面。
  - 预期：展开状态仍对应原风险；计时器停止更新。
- [ ] **场景 5：仓库卫生**
  - 操作：执行 `git status --short --ignored`、`git ls-files`、`git log --oneline -10`。
  - 预期：个人文件显示为已忽略；调试产物不在跟踪列表；提交历史为基线 + 主题提交。

## 验收标准覆盖

| spec 验收标准 | checklist 条目 |
|---|---|
| AC1 | F1 调试残留 |
| AC2 | F2 失效 Javadoc |
| AC3 | F3 硬停止 / 尽力解析 / 降级结果 / 场景 1 |
| AC4 | F4 准入作用域 / 场景 2 |
| AC5 | F5 个人文件策略 |
| AC6 | F6 异常分类 |
| AC7 | F7 URL 加固 |
| AC8 | F8 可观测性 / 场景 2 |
| AC9 | F9 截断准确 / 场景 3 |
| AC10 | F10 展开状态 / 场景 4 |
| AC11 | F11 计时器 / 场景 4 |
| AC12 | F12 GET 边界 / 场景 2 |
| AC13 | F13 产物清理 / 场景 5 |
| AC14 | F14 提交序列 / 场景 5 |
| AC15 | 编译与测试第 1 项 |
| AC16 | 编译与测试第 2、3 项 |
| AC17 | 编译与测试第 5 项 / F3 硬停止 |
| AC18 | docs/specs 目录四份文档存在 |
