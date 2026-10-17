# ReviewPilot 质量修复 Tasks（2026-10-17）

对应 spec：`docs/specs/2026-10-17-fix-batch/spec.md`
对应 plan：`docs/specs/2026-10-17-fix-batch/plan.md`

## 文件清单

| 操作 | 文件 | 职责 |
|------|------|------|
| 修改 | `backend/src/main/java/com/reviewpilot/service/ai/ReviewAgent.java` | 预算耗尽硬停止 + 尽力解析 |
| 修改 | `backend/src/main/java/com/reviewpilot/config/ApiSecurityFilter.java` | API 路径判断、审查额度、MDC |
| 修改 | `backend/src/main/java/com/reviewpilot/controller/ApiExceptionHandler.java` | 日志携带 requestId |
| 修改 | `backend/src/main/java/com/reviewpilot/service/github/GithubPrFetcher.java` | 异常分类 + 探测页 |
| 修改 | `backend/src/main/java/com/reviewpilot/model/PrUrl.java` | 拒绝 `.`/`..` |
| 修改 | `backend/src/main/resources/application.yml` | requestId 日志格式 |
| 修改 | `backend/src/test/java/com/reviewpilot/service/ai/ReviewAgentDeadlineTest.java` | 硬停止回归测试 |
| 修改 | `backend/src/test/java/com/reviewpilot/config/ApiAdmissionTest.java` | 路径/额度/requestId 测试 |
| 修改 | `backend/src/test/java/com/reviewpilot/controller/ApiSecurityTest.java` | 响应头与非 API 路径测试 |
| 修改 | `backend/src/test/java/com/reviewpilot/service/github/GithubPrFetcherTest.java` | 探测页 + 异常分类 |
| 修改 | `backend/src/test/java/com/reviewpilot/model/PrUrlTest.java` | `.`/`..` 用例 |
| 修改 | `frontend/src/components/RiskList.vue` | 内容键展开状态 |
| 修改 | `frontend/src/App.vue` | 卸载时清理计时器 |
| 新建 | `frontend/src/utils/risk.js` | `riskKey` 纯函数 |
| 新建 | `frontend/src/utils/risk.test.js` | Node 内置测试 |
| 修改 | `.gitignore` | 个人文件与产物忽略 |
| 修改 | `docs/security-deployment.md` | GET 限流边界措辞 |
| 删除 | 根目录 4 张 PNG、`.playwright-mcp/page-*.yml` | 清理调试产物 |
| 新建 | `docs/specs/2026-10-17-fix-batch/checklist.md` | 验收清单 |

## T1: 确认基线状态

**文件：** 无
**依赖：** 无
**步骤：**
1. 在仓库根执行 `git status --short`，记录 19 个修改文件和未跟踪清单。
2. 在 `backend/` 执行 `mvn -o -q test`，记录测试总数。

**验证：** 测试输出为 202 tests、0 failure / 0 error / 0 skipped；`git status` 与 spec 背景一致。

## T2: 暂存基线文件

**文件：** 见步骤
**依赖：** T1
**步骤：**
1. 暂存后端安全功能与修改：
   - `backend/src/main/java/com/reviewpilot/config/ApiSecurityFilter.java`
   - `backend/src/main/java/com/reviewpilot/config/ProductionSecurityConfig.java`
   - `backend/src/main/java/com/reviewpilot/config/WebCorsConfig.java`
   - `backend/src/main/java/com/reviewpilot/controller/ApiExceptionHandler.java`
   - `backend/src/main/java/com/reviewpilot/model/ReviewResult.java`
   - `backend/src/main/java/com/reviewpilot/pipeline/ReviewPipeline.java`
   - `backend/src/main/java/com/reviewpilot/service/ai/DeepSeekProvider.java`
   - `backend/src/main/java/com/reviewpilot/service/ai/ToolRegistry.java`
   - `backend/src/main/java/com/reviewpilot/service/context/ContextSlice.java`
   - `backend/src/main/java/com/reviewpilot/service/diff/DiffHunk.java`
   - `backend/src/main/java/com/reviewpilot/service/diff/FileChange.java`
   - `backend/src/main/java/com/reviewpilot/service/github/FetchedFiles.java`
   - `backend/src/main/java/com/reviewpilot/service/github/FileContentFetcher.java`
   - `backend/src/main/java/com/reviewpilot/service/github/GithubPrFetcher.java`
   - `backend/src/main/resources/application.yml`
   - `backend/src/test/java/com/reviewpilot/config/ApiAdmissionTest.java`
   - `backend/src/test/java/com/reviewpilot/config/ProductionSecurityTest.java`
   - `backend/src/test/java/com/reviewpilot/controller/ApiSecurityTest.java`
   - `backend/src/test/java/com/reviewpilot/controller/ErrorSanitizationTest.java`
   - `backend/src/test/java/com/reviewpilot/controller/ReviewControllerErrorTest.java`
   - `backend/src/test/java/com/reviewpilot/model/ReviewResultTest.java`
   - `backend/src/test/java/com/reviewpilot/service/ai/DeepSeekProviderTest.java`
   - `backend/src/test/java/com/reviewpilot/service/ai/ToolRegistryTest.java`
   - `backend/src/test/java/com/reviewpilot/service/context/ContextSliceTest.java`
   - `backend/src/test/java/com/reviewpilot/service/diff/DiffSnapshotTest.java`
2. 暂存前端与文档：
   - `frontend/src/App.vue`、`frontend/src/api/review.js`、`frontend/src/api/review.test.js`
   - `docs/security-deployment.md`、`docs/superpowers/plans/2026-09-17-api-security.md`
   - `docs/current-design-report.md`、`docs/architecture-eli5.html`
   - `start.ps1`、`start-reviewpilot.bat`
   - `AGENTS.md`、`.claude/agents/*`、`backend/.claude/agents/*`、`frontend/.claude/agents/*`
   - `.gitignore`（仅当前已有的 `.reviewpilot.env` 一行）
3. 明确**不暂存**：`.claude/settings.local.json`、`.codex/`、`.mewcode/`、根目录 4 个问题笔记、`interview-guide-fullstack-intern.md`、`docs/interview-questions.md`、`docs/security-access-mobile.png`。

**验证：** `git status --short` 中暂存区只包含上述文件；个人文件仍为未跟踪。

## T3: 创建基线提交

**文件：** 无
**依赖：** T2
**步骤：**
1. 执行提交，消息：
   ```
   feat(security): add access admission, production guardrails, and error sanitization

   - Bearer 鉴权与单实例审查准入（10/min，2 并发），401/429 契约
   - 生产 profile 强制开启鉴权并限定仓库范围
   - 上游错误与工具异常脱敏，不记录响应体
   - ReviewResult/ContextSlice/DiffHunk/FileChange/FetchedFiles 不可变快照
   - 前端运行时访问令牌入口与 401 引导

   Co-Authored-By: Claude Code <noreply@anthropic.com>
   ```

**验证：** `git show --stat HEAD` 与 T2 清单一致；`git status --short` 不再显示已暂存文件。

## T4: 校验 Javadoc 反向补丁

**文件：** 无
**依赖：** T3
**步骤：**
1. 生成 `0eb1810` 的补丁到临时文件：`git show 0eb1810 --format= > /tmp/javadoc.patch`。
2. 执行 `git apply --check -R /tmp/javadoc.patch`。

**验证：** 命令退出码为 0；若失败，改用 T5 的回退脚本（按文件删除新增的一行注释）。

## T5: 删除 64 条重复 Javadoc

**文件：** `0eb1810` 涉及的 64 个 Java 文件
**依赖：** T4
**步骤：**
1. 执行 `git apply -R /tmp/javadoc.patch`。
2. 将 `ReviewPilotApplication.java` 中的 `public class  ReviewPilotApplication` 恢复为单空格。
3. 检查 `git diff --stat`，确认只删除注释并保留原有 Javadoc。

**验证：** `grep -rn "ReviewPilot 应用的 Spring Boot 启动类" backend/src/main/java` 无结果；`git diff --stat` 为 64 个文件、仅删除行。

## T6: 删除流水线调试注释

**文件：** `backend/src/main/java/com/reviewpilot/pipeline/ReviewPipeline.java`
**依赖：** T5
**步骤：**
1. 删除 `//UserService.java`、`//ReviewPipeline.java`、`//application.yml` 三行及其多余空行。

**验证：** `grep -n "UserService.java\|//ReviewPipeline.java\|//application.yml"` 无结果；`mvn -o -q -DskipTests compile` 通过。

## T7: 扫描确认注释位置

**文件：** 无
**依赖：** T6
**步骤：**
1. 对 T5 涉及的 64 个文件运行检查：存在“上一行以 `@` 开头、下一行以 `/**` 开头”的模式即报告。
2. 复查 T5 产生的 diff，确认没有误删原有 Javadoc。

**验证：** 扫描结果为空；`mvn -o -q -DskipTests test-compile` 通过。

## T8: 提交文档清理

**文件：** 无
**依赖：** T7
**步骤：**
1. 暂存 T5/T6 的改动。
2. 提交消息：
   ```
   docs: remove duplicate Javadoc that javadoc cannot associate

   0eb1810 added a second doc comment between each annotation and class
   declaration. javadoc only associates the comment before the annotation,
   so the additions were dead and duplicated the existing docs. Reverting
   the comment-only commit keeps one authoritative description per class.

   Co-Authored-By: Claude Code <noreply@anthropic.com>
   ```

**验证：** `git show --stat HEAD` 包含 64 个 Java 文件与 `ReviewPipeline.java`。

## T9: 实现预算耗尽硬停止

**文件：** `backend/src/main/java/com/reviewpilot/service/ai/ReviewAgent.java`
**依赖：** T8
**步骤：**
1. 新增 `stopAtBudget(List<Message> messages, Loop loop)`：
   - 从后向前查找 `role=assistant` 且 `content` 非空的最后一条消息；
   - 找到则调用 `replyReader.parse(content)`，捕获 `JsonProcessingException`；
   - 解析成功返回结果，失败或没有消息时返回降级 `ReviewResult`。
2. 将循环顶部的过期分支改为 `return finish(stopAtBudget(messages, loop), loop);`，不再追加“请立即输出”的用户消息。
3. 工具调用期间的过期分支保持不执行工具；本轮结束后由顶部检查进入 `stopAtBudget`。
4. 保留 `forceComplete` 给上下文超限与最大轮次路径。

**验证：** `mvn -o -q -DskipTests compile` 通过。

## T10: 更新预算测试

**文件：** `backend/src/test/java/com/reviewpilot/service/ai/ReviewAgentDeadlineTest.java`
**依赖：** T9
**步骤：**
1. 将 `an_expired_budget_answers_immediately_without_calling_tools` 改为断言 `modelProvider.chat` **从未被调用**，summary 含“预算耗尽”。
2. 新增用例：第一次 chat 返回带合法 JSON 的 tool call，并在调用期间超过预算；断言第二次 chat 不发生、结果采用该 JSON。
3. 新增用例：最后一条 assistant 内容不是 JSON；断言降级结果、summary 含“预算耗尽”、`risks` 为空。

**验证：** `cd backend && mvn -o -q -Dtest=ReviewAgentDeadlineTest test` 通过。

## T11: 回归现有 Agent 测试并提交

**文件：** 无
**依赖：** T10
**步骤：**
1. 运行 `mvn -o -q -Dtest=ReviewAgentTest,ReviewAgentDeadlineTest test`。
2. 暂存 `ReviewAgent.java` 与测试，提交：
   ```
   fix(agent): stop model calls when request budget is exhausted

   The expired branch called forceComplete, which started another paid
   model round-trip and contradicted the deadline contract. The loop now
   reuses the last assistant message without a repair call, or returns a
   degraded result that keeps deterministic rule findings.

   Co-Authored-By: Claude Code <noreply@anthropic.com>
   ```

**验证：** 测试通过；`git show --stat HEAD` 仅包含两个文件。

## T12: 收窄过滤器作用域并加入 requestId

**文件：** `backend/src/main/java/com/reviewpilot/config/ApiSecurityFilter.java`
**依赖：** T11
**步骤：**
1. 在 `doFilterInternal` 开头计算 path；非 `/api/` 请求直接放行。
2. 为 API 请求生成 UUID，写 `X-Request-ID` 响应头，`MDC.put("requestId", id)`；用 `try/finally` 保证 `MDC.remove`。
3. 保留健康检查与预检放行、Bearer 鉴权。
4. 仅对 `POST /api/review` 执行 `acquire/release`；其他请求直接放行。

**验证：** `mvn -o -q -DskipTests compile` 通过。

## T13: 配置日志格式与异常日志

**文件：** `backend/src/main/resources/application.yml`、`backend/src/main/java/com/reviewpilot/controller/ApiExceptionHandler.java`
**依赖：** T12
**步骤：**
1. 在 `logging.pattern` 下增加 `level: "%5p [%X{requestId:-}]"`。
2. 在 `ApiExceptionHandler` 的 GitHub/AI 失败日志中加入 `requestId={}` 与 `MDC.get("requestId")`。

**验证：** `mvn -o -q -DskipTests compile` 通过。

## T14: 增加过滤器测试

**文件：** `backend/src/test/java/com/reviewpilot/config/ApiAdmissionTest.java`、`backend/src/test/java/com/reviewpilot/controller/ApiSecurityTest.java`
**依赖：** T13
**步骤：**
1. 新增用例：POST 到非 `/api/review` 路径不消耗审查额度，后续 `/api/review` 仍可准入。
2. 新增用例：非 API 路径在鉴权开启时不被拒绝。
3. 在 `ApiSecurityTest` 中断言 API 响应带 `X-Request-ID`。
4. 新增用例：请求处理期间 `MDC.get("requestId")` 非空，请求结束后被清除。

**验证：** `mvn -o -q -Dtest=ApiAdmissionTest,ApiSecurityTest,ErrorSanitizationTest test` 通过。

## T15: 提交准入与日志修复

**文件：** 无
**依赖：** T14
**步骤：**
1. 暂存过滤器、异常处理器、`application.yml` 与测试。
2. 提交：
   ```
   fix(security): scope admission to review submissions and correlate logs

   The servlet filter treated every POST as a review and authenticated all
   paths. It now acts only on /api/*, charges quota only for POST
   /api/review, and propagates a server-generated request id through MDC
   and the response header so sanitized errors remain traceable.

   Co-Authored-By: Claude Code <noreply@anthropic.com>
   ```

**验证：** `git show --stat HEAD` 仅包含预期文件。

## T16: 分类 GitHub 抓取异常

**文件：** `backend/src/main/java/com/reviewpilot/service/github/GithubPrFetcher.java`
**依赖：** T15
**步骤：**
1. 增加 `isTransportFailure(RuntimeException)`：识别 `WebClientRequestException`、`DataBufferLimitException`、以及消息以 "Timeout on blocking read" 开头的 `IllegalStateException`。
2. `fetchFilePage` 的 `catch (RuntimeException)` 改为：传输故障包装 `GithubApiException`；其他异常原样抛出。
3. `fetchPrTitle` 使用同一判断：传输故障记录 WARN 并返回 null；编程错误继续抛出。

**验证：** `mvn -o -q -DskipTests compile` 通过。

## T17: 修正分页截断

**文件：** `backend/src/main/java/com/reviewpilot/service/github/GithubPrFetcher.java`
**依赖：** T16
**步骤：**
1. 正常翻页结束后，若最后一页恰好满 100 且已到 `max-file-pages`，探测第 `max-file-pages + 1` 页。
2. 探测页为空 → `complete=true`；非空 → `truncated=true`，且探测内容不加入结果。

**验证：** `mvn -o -q -DskipTests compile` 通过。

## T18: 加固 PR URL

**文件：** `backend/src/main/java/com/reviewpilot/model/PrUrl.java`
**依赖：** T17
**步骤：**
1. 紧凑构造器中，owner 或 repo 等于 `.` 或 `..` 时抛出 `IllegalArgumentException`。

**验证：** `mvn -o -q -DskipTests compile` 通过。

## T19: 更新 GitHub 测试

**文件：** `backend/src/test/java/com/reviewpilot/service/github/GithubPrFetcherTest.java`、`backend/src/test/java/com/reviewpilot/model/PrUrlTest.java`
**依赖：** T18
**步骤：**
1. 更新满载截断测试：为探测页入队响应。
2. 新增用例：整页倍数且探测页为空 → `truncated=false`。
3. 新增用例：探测页非空 → `truncated=true`。
4. 新增用例：`DiffParser` 抛编程错误时原样传播，不包装为 `GithubApiException`。
5. 新增用例：传输超时包装为 `GithubApiException`（MockWebServer 无响应 + 短 timeout）。
6. 新增 `PrUrl` 的 `.`/`..` 拒绝用例。

**验证：** `cd backend && mvn -o -q -Dtest=GithubPrFetcherTest,PrUrlTest test` 通过。

## T20: 提交 GitHub 修复

**文件：** 无
**依赖：** T19
**步骤：**
1. 暂存 `GithubPrFetcher.java`、`PrUrl.java` 与测试。
2. 提交：
   ```
   fix(github): classify failures, validate PR segments, and detect truncation accurately

   - Only transport failures become 502; programming errors surface as 500.
   - A full last page is probed once, so exact multiples of 100 are not
     reported as truncated.
   - owner/repo segments "." and ".." are rejected before any API call.

   Co-Authored-By: Claude Code <noreply@anthropic.com>
   ```

**验证：** `git show --stat HEAD` 仅包含预期文件。

## T21: 新增前端风险键

**文件：** `frontend/src/utils/risk.js`、`frontend/src/utils/risk.test.js`
**依赖：** T20
**步骤：**
1. 新增 `riskKey(risk)`，返回 `file|line|message`。
2. 新增 `node:test` 用例：正常风险、缺字段风险、相同风险稳定输出。

**验证：** `cd frontend && node --test src/utils/risk.test.js` 通过。

## T22: 改造风险列表展开状态

**文件：** `frontend/src/components/RiskList.vue`
**依赖：** T21
**步骤：**
1. 引入 `riskKey`。
2. `expanded` 与 `copied` 改用 `riskKey` 作为键。
3. `v-for :key` 改为 `riskKey(risk)`；`aria-controls` 保留渲染下标。

**验证：** `npm run build` 通过；代码中不再用 `i` 作为展开/复制状态键。

## T23: 清理计时器

**文件：** `frontend/src/App.vue`
**依赖：** T22
**步骤：**
1. 引入 `onBeforeUnmount`，注册 `stopTimer`。

**验证：** `npm run build` 通过。

## T24: 运行前端验证并提交

**文件：** 无
**依赖：** T23
**步骤：**
1. 运行 `node --test src/utils/risk.test.js src/api/review.test.js`。
2. 运行 `npm run build`。
3. 暂存前端改动，提交：
   ```
   fix(frontend): key risk expansion by content and clear timer on unmount

   Expansion and copy feedback were keyed by the filtered array index, so
   changing the severity filter could target another risk. Keys now match
   the backend dedupe key. The elapsed timer is cleared on unmount.

   Co-Authored-By: Claude Code <noreply@anthropic.com>
   ```

**验证：** 前端测试与构建通过；`git show --stat HEAD` 仅包含前端文件。

## T25: 更新忽略规则

**文件：** `.gitignore`
**依赖：** T24
**步骤：**
1. 增加：`.playwright-mcp/`、`.claude/settings.local.json`、`.codex/`、`.mewcode/`。
2. 增加根目录 4 个问题笔记、面试文档、根目录 4 张 PNG、`docs/security-access-mobile.png` 的精确路径。

**验证：** `git check-ignore -v` 对每个目标路径返回匹配规则。

## T26: 移除调试产物

**文件：** 根目录 4 张 PNG、`.playwright-mcp/page-*.yml`
**依赖：** T25
**步骤：**
1. 执行 `git rm empty-state.png light-empty.png light-result.png result-full.png`。
2. 执行 `git rm .playwright-mcp/page-*.yml`。
3. 保留 `.playwright-mcp/` 下的未跟踪日志文件（已被忽略）。

**验证：** `git ls-files '*.png'` 不再包含根目录 4 张；`git ls-files '.playwright-mcp/*'` 为空。

## T27: 明确 GET 限流边界

**文件：** `docs/security-deployment.md`
**依赖：** T26
**步骤：**
1. 在"配额与边界"章节明确写出：应用层只对 POST `/api/review` 限流；GET `/api/pr/files` 不限流，由网关统一限速。

**验证：** 文档中存在该表述，且与代码行为一致。

## T28: 全量验证

**文件：** 无
**依赖：** T27
**步骤：**
1. 运行 `cd backend && mvn -o -q test`。
2. 运行 `cd frontend && node --test src/utils/risk.test.js src/api/review.test.js`。
3. 运行 `cd frontend && npm run build`。
4. 运行 `git diff --check`。

**验证：** 后端测试 ≥202 且 0 failure/error；前端测试与构建通过；无空白错误。

## T29: 提交仓库卫生

**文件：** 无
**依赖：** T28
**步骤：**
1. 暂存 `.gitignore`、删除记录、部署文档。
2. 提交：
   ```
   chore(repo): remove debug artifacts and ignore local files

   Screenshots and Playwright snapshots were committed during the frontend
   rewrite. They are removed from the current tree, local tooling and
   personal notes are ignored, and the deployment doc states the GET
   rate-limit boundary explicitly.

   Co-Authored-By: Claude Code <noreply@anthropic.com>
   ```

**验证：** `git show --stat HEAD` 仅包含预期文件；`git status --short --ignored` 中个人文件显示为 `!!`。

## T30: 提交 spec 文档

**文件：** `docs/specs/2026-10-17-fix-batch/`
**依赖：** T29
**步骤：**
1. 暂存 spec.md、plan.md、task.md、checklist.md。
2. 提交：
   ```
   docs(spec): record the quality-fix spec, plan, tasks, and checklist

   Co-Authored-By: Claude Code <noreply@anthropic.com>
   ```

**验证：** `git show --stat HEAD` 包含四份文档。

## 执行顺序

```
T1 → T2 → T3
          ├→ T4 → T5 → T6 → T7 → T8
          │                       ├→ T9 → T10 → T11
          │                       │                ├→ T12 → T13 → T14 → T15
          │                       │                │                        ├→ T16 → T17 → T18 → T19 → T20
          │                       │                │                        │                        ├→ T21 → T22 → T23 → T24
          │                       │                │                        │                        │                        ├→ T25 → T26 → T27 → T28 → T29
          │                       │                │                        │                        │                        │                        └→ T30
```
