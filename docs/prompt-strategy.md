# Prompt 策略与 Token 预算

> 本文解释 ReviewPilot 为什么不把整团 diff 直接交给 LLM、按 FileType 分流的具体做法、Token 预算与截断策略，以及 rule + AI risks 如何合并。配合 [`architecture.md`](architecture.md) 阅读。

## 设计原则

**LLM 不擅长的事不要让 LLM 干**：

- 死板的模式匹配（lock 未释放、catch 空块）→ 规则层稳定命中，0 token 成本，可解释
- 跨文件语义判断（这个 service 改动会不会破坏 controller 的契约）→ LLM 拿手
- 全文档总结 → LLM 拿手

所以 ReviewPilot 不是一个"调 LLM 的壳子"，而是一个**规则层兜底 + LLM 做语义增强**的混合系统。Prompt 的全部价值，是让 LLM 明白：
1. 看哪些文件、按什么角色看；
2. 哪些问题规则已经标了，不要重复造，要在它基础上深挖；
3. 输出必须是严格 JSON，前端能直接渲染。

## System Prompt：契约不要让模型猜

`PromptBuilder.systemPrompt()` 用三个手段锁住输出：

1. **Schema 直接贴进 prompt**：模型不需要"理解"返回什么字段，复制就好。
2. **JSON-only**：明确禁止 prose / code fence / markdown，配合后处理 `parseModelReply` 提取 `{...}` 子串再解析（容忍 LLM 偶尔在前后多说两句）。
3. **空数组合法**：避免模型"为了不空着"瞎编风险点。

```
Empty risks/suggestions arrays are fine — do not invent issues.
```

这一句对幻觉抑制效果意外地大。

## User Prompt：按 FileType 分组

文件按 `FileType` 分组，每组前面挂一段 `PromptTemplate.guidance(type)`，组内是各文件的：风险预标 → 上下文窗口 → patch。

伪结构：

```
Below are the changed files of a Pull Request, grouped by file type.
For each group, follow the role-specific guidance, then review each file.

## Group: CONTROLLER
These files are HTTP-facing controllers. Pay attention to:
- Input validation ...
- Status codes & error responses ...

### FILE: src/main/.../FooController.java (modified, +12 -3)
Pre-detected risks (from static rules):
  - [MEDIUM] line 42: Bare catch block ...
Context (lines around the risks):
  --- 38-46 ---
  ... raw lines from the diff hunk ±3 ...
Patch:
@@ -38,5 +38,12 @@ ...

## Group: SQL
These files are SQL / migrations. Pay attention to:
...
```

### 为什么按类型分组

- **同 PR 通常跨多种文件**：controller 改了、service 跟着改、test 也加了。如果一次 prompt 没有上下文，模型就只能给"通用建议"。
- **每个文件单独调一次 LLM 太贵也没必要**：同一个 PR 的 summary 是全局的，分文件调用会让 summary 重复且彼此割裂。
- **分组是折中**：单次调用 + 同组文件共享 guidance，模型读 controller 时自带 controller 思维，读 SQL 时自带 SQL 思维。

### 各组 guidance 的取舍

`PromptTemplate` 的每条 guidance 都是**具体的、可执行的检查项**，避免"写好代码"这类废话——模型把具体词当指令、把废话当噪声。

| FileType | 关键检查点 |
|---|---|
| CONTROLLER | 入参校验 / 状态码 / 鉴权 / 幂等 |
| SERVICE | 事务边界 / 并发 / 异常传播 / N+1 |
| CONFIG | 硬编码密钥 / 不安全默认 / 跨环境漂移 |
| SQL | 索引影响 / 迁移安全 / 向后兼容 / 注入面 |
| TEST | 边界覆盖 / 脆弱性 / Mock 失真 |
| OTHER | 通用代码质量 |

完整文本见 `backend/src/main/java/com/reviewpilot/service/prompt/PromptTemplate.java`。

## 预标风险：让规则结果先进 prompt

每个文件如果命中了规则，会先以 `Pre-detected risks` 块出现在 patch 之前：

```
Pre-detected risks (from static rules):
  - [MEDIUM] line 42: Bare catch block ...
```

System prompt 明确要求：

> Treat the pre-detected risks as authoritative starting points; you should
> re-state them in your output (with file/line) and add deeper findings on top.

这相当于**让规则把 LLM 的注意力先聚焦到已知问题点附近**——LLM 会更倾向于看那一段 patch、给出更具体的二次发现，而不是泛泛而谈。

## 上下文窗口：用 hunk ±3 行已足够

`ContextLoader` 不调 GitHub raw API 拉文件全文，只从 `DiffHunk` 自身的 ±3 行 context 切片（GitHub 默认就给 3 行 unified diff context）。

这是个**省 Token 又够用**的选择：
- 拉文件全文：100KB 文件 ≈ 25k tokens，单个文件就吃光预算
- 只看 patch：失去局部环境（这个变量在哪声明的、上面的函数签名是什么）
- hunk ±3 行：保留最直接的局部环境，对启发式风险点位足够，规则命中后再看上下文也只多 6 行

## Token 预算：多级护栏

LLM 的输入有限（DeepSeek `deepseek-chat` 约 64K tokens 输入），且越长越慢越贵。`PromptBuilder` 用三层护栏：

| 护栏 | 值 | 作用 |
|---|---|---|
| 单文件 patch 字符上限 | `MAX_PATCH_CHARS_PER_FILE = 6_000` | 阻止一个 1MB 的 yaml 把所有别的文件挤掉 |
| 整体 prompt 字符上限 | `MAX_TOTAL_CHARS = 60_000` | 整体不超 ~16k tokens（按 4 字符/token 估），给输出留 4k 余量 |
| 模型 max-tokens | `application.yml` `max-tokens: 4096` | 输出本身的硬上限 |

被截断时会显式标记，模型与人都看得见：

- 单文件超长 → 该文件 patch 末尾 `[...truncated N chars]`
- 总长超限 → 整段末尾 `[truncated: remaining files omitted to stay within prompt budget]`

> 这是字符数预算而不是真实 tokenizer 计数。**字符数估算 ≈ 4× tokens** 是 DeepSeek/中英文混合的经验值；保留 ~25% 余量后，60k 字符上限远低于真实输入上限，没必要为了精确性引入 tokenizer 库（PR#9 评估过、性价比不够）。

## 输出后处理：rule + AI risks 合并去重

`ReviewPipeline.parseModelReply` 拿到 LLM JSON 后：

1. **抗前导/尾随文字**：用 `extractJsonObject` 取 `{...}` 子串再 parse，模型偶尔返回 `Sure, here is the review: { ... }` 也不会炸。
2. **risks 合并**：把 LLM 的 risks 与规则层的 risks 按 `(file, line, message)` 元组做集合去重——**规则结果优先**，LLM 重复的会被丢掉。
3. **suggestions 不去重**：LLM 给的 suggestion 通常带语义上下文，规则层不产生 suggestion，无需合并。

> 规则优先的 reasoning：规则结果 deterministic、可解释、可测试。LLM 结果是"补充"而不是"替代"。

## PR#9 已落地的强化

针对评测反馈，PR#9 落地了五项强化：

1. **PromptBuilder.systemPrompt() 加"Behavior-change checklist"**：对每处 catch/throw 改动追问 4 题（异常类型被吞 / catch 范围过宽 / cause 链丢失 / 调用方丢失区分能力），任一命中必须出 risk。同时引导模型反思"修复是否在正确层"——根因若在更深层，本层 catch-and-translate 是创可贴而非修复。
2. **PromptBuilder.systemPrompt() 加 "Severity rubric"**：明确 HIGH = 行为/语义改变（异常被吞或转换、错误模式坍塌、并发不变式弱化、契约破坏），明确"行为变化 → HIGH，不要为了客气降级"。
3. **新增 `ExceptionSwallowingRule`（service/risk/rules/）**：检测 `catch X → throw new Y` 链路。Y 不传 X 作 cause → HIGH（stack trace 永久丢失）；传 cause → MEDIUM（链路在但语义被重新映射）。规则与 `BareCatchRule` 互补，后者抓静默吞噬，本规则抓"看似处理实则吞掉真实类型"。
4. **PromptBuilder.systemPrompt() 加 "NPE risk—context-aware"**：标注 NPE 前先扫 Context 块的 null 守卫（`if (x != null)` / `Objects.requireNonNull` / `Optional` / 三元守卫）。命中守卫 → 不报；无 Context → 降为 LOW + 明确写"no visible null guard in context"。理由：假阳性 NPE 警告会快速烧掉 reviewer 对系统的信任。
5. **PromptBuilder.systemPrompt() 加 "Cause-inference fragility"**：检测四种从异常类型反推语义的模式（`catch X` / `instanceof X` / 走 `getCause()` 链 / `findCause(_, X.class)`），让模型显式问"调用 API 是否承诺 X→cause 1:1 映射"。不承诺则至少 MEDIUM；分类错误产生误导用户消息则 HIGH。引导建议至根因层显式判断，对应评测里 Maintainer 一眼识破的 Dubbo Serializable 案例。

PromptBuilder 的 token 预算在 PR#9 也从硬编码升级为可配置：`reviewpilot.prompt.budget.max-patch-chars-per-file`（默认 6000）和 `reviewpilot.prompt.budget.max-total-chars`（默认 60000）。演示遇到长 PR 时直接调 application.yml 即可，不需重编。

## 已知局限

仍未解决的方向（留待后续）：

- **跨文件全图**：当前只看 PR 改动 + ±3 行。"应在 X 层修复"的判断仍依赖模型常识，不能引用同仓库 X 层的真实代码。下一步可加按命中类名拉取 GitHub `repos/{o}/{r}/contents/{path}` 的 ContextLoader 增强。
- **Tokenizer 精度**：用字符数 / 4 估 token，跨语言混合内容偏差最大可达 30%。引入真实 tokenizer 库可压缩这块的安全余量、塞下更长的 PR——但 3 天周期内性价比不够高。
- **规则误报**：纯启发式 patch-only，遇到 lambda 单行写法、注解配置式锁、aspectj 织入的 @Transactional 等场景会漏。AST 级分析是后续工程化方向。
