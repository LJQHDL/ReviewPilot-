# ReviewPilot 质量修复 Checklist（2026-10-17）

> 每一项通过运行代码或观察行为来验证，聚焦系统行为。
> 验收执行时间：2026-10-17；验收提交：`888c1ab`。

## 实现完整性

- [x] F1 调试残留已删除：`grep -rn "UserService.java" backend/src/main/java` 无结果，后端编译通过。
- [x] F2 失效 Javadoc 已删除：全量扫描 `backend/src/main/java`，不存在"注解行后紧跟 `/**`"的模式（0 个文件）；64 个文件仅删除 64 行注释。
- [x] F3 预算耗尽硬停止：`ReviewAgentDeadlineTest` 断言过期预算下 `ModelProvider.chat` 调用次数不增加。
- [x] F3 尽力解析：最后一次 assistant 回复为合法 JSON 时被采用（`an_expired_budget_reuses_the_last_assistant_reply`）。
- [x] F3 降级结果：无法解析时 summary 含"时间预算已用尽"；`ReviewPipelineFlowTest.a_budget_degraded_review_still_carries_rule_risks` 证明规则风险仍进入最终结果。
- [x] F4 准入作用域：`ApiAdmissionTest` 覆盖非审查 POST 不占额度、非 API 路径不鉴权；`ApiSecurityTest` 覆盖 429 与健康检查放行。
- [x] F5 个人文件策略：`AGENTS.md`、`.claude/agents/*`、`backend/.claude/agents/*`、`frontend/.claude/agents/*` 已跟踪；`git check-ignore` 命中所有个人文件与本地配置。
- [x] F6 异常分类：`GithubPrFetcherTest.programming_errors_are_not_mapped_to_upstream_failures` 断言编程错误原样抛出；`transport_timeouts_are_mapped_to_api_exception` 断言超时映射为 `GithubApiException`。
- [x] F7 URL 加固：`PrUrlTest.rejects_dot_segments_in_owner_or_repo` 覆盖 `.`、`..`、`%2e%2e`。
- [x] F8 可观测性：`ApiSecurityTest` 断言 `X-Request-ID`；`ApiAdmissionTest.requestIdIsAvailableDuringTheRequestAndClearedAfterwards` 断言 MDC 生命周期；日志 pattern 输出 `[requestId]`（运行日志可见）。
- [x] F9 截断准确：`exact_full_page_at_the_cap_is_not_reported_as_truncated` 与 `reports_truncated_instead_of_passing_off_a_partial_fetch_as_complete` 均通过。
- [x] F10 展开状态：`risk.test.js` 覆盖内容键在过滤切换后仍指向同一风险（未运行浏览器，证据为单元测试 + `npm run build`）。
- [x] F11 计时器：`App.vue` 注册 `onBeforeUnmount(stopTimer)`，构建通过（未运行浏览器，证据为代码 + 构建）。
- [x] F12 GET 边界：`docs/security-deployment.md:48` 明确应用层只对 `POST /api/review` 限流；`ApiSecurityTest` 覆盖 GET 需鉴权。
- [x] F13 产物清理：`git ls-files '*.png' '.playwright-mcp/*'` 为空；`.gitignore` 命中对应路径。
- [x] F14 提交序列：基线提交 `f4ac830` + 6 个主题提交 + 1 个测试提交，每个提交均可编译。

## 集成

- [x] `ReviewPipeline` 收到降级结果后仍合并规则风险（`a_budget_degraded_review_still_carries_rule_risks`）。
- [x] `ApiSecurityFilter` → Controller → `ApiExceptionHandler` 的 requestId 一致（运行日志中 `[<uuid>]` 与响应头一致；过滤器直接调用时 `requestId=null` 属预期）。
- [x] `GithubPrFetcher` 探测页内容不进入文件列表（`reports_truncated...` 断言结果仍为 100 个文件）。
- [x] `RiskList` 使用 `riskKey` 作为展开/复制状态键（单测 + 构建通过）。
- [x] 公开接口仍有真实调用方（`mvn -o test` 全绿，214 个测试）。

## 编译与测试

- [x] `cd backend && mvn -o test`：214 tests，0 failure / 0 error / 0 skipped（基线 202）。
- [x] `cd frontend && node --test src/utils/risk.test.js src/api/review.test.js`：5 tests，0 fail。
- [x] `cd frontend && npm run build`：vite 构建成功（129.41 kB JS / 20.47 kB CSS）。
- [x] `git diff --check`：无空白错误。
- [x] `pom.xml` / `package.json` / `package-lock.json` 无改动（`git diff` 为空）。

## 端到端场景

- [x] **场景 1：预算耗尽降级** — 单元测试与流水线集成测试验证：不再调用模型，summary 含"时间预算已用尽"，规则风险保留。
- [x] **场景 2：鉴权与配额** — `ApiSecurityTest`：无令牌 401、合法令牌 200、第二次 429 + `Retry-After`、GET 不受审查配额影响、响应带 `X-Request-ID`。
- [x] **场景 3：GitHub 分页边界** — `GithubPrFetcherTest`：整页倍数探测为空 → `truncated=false`；探测非空 → `truncated=true`；探测页只请求一次。
- [x] **场景 4：前端过滤与卸载** — 过滤稳定性由 `risk.test.js` 覆盖；卸载清理由 `onBeforeUnmount` 代码与构建验证（未跑浏览器）。
- [x] **场景 5：仓库卫生** — `git status --short` 干净；`git ls-files` 无调试产物；提交历史为基线 + 主题提交。

## 验收结果

- 通过：全部条目。
- 未通过：无。
- 说明：场景 4 的浏览器观察未执行，用单元测试与构建替代；其余条目均有命令输出或测试断言作为证据。
