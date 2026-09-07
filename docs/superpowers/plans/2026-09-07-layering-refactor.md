# Java 分层职责重构计划

用户已授权发现职责混淆后直接重构。采用当前会话内执行，保留 REST 契约与现有包体系，不增加数据库或框架。

## 设计

- Controller 只做 HTTP 输入与调用；统一异常映射放 ApiExceptionHandler，以异常类型识别认证失败。
- ReviewPipeline 保留应用流程编排；ReviewReplyReader 统一审查 JSON 解析、一次格式修复与降级；RiskMerger 管理合并规则。
- ReviewAgent 返回 AgentReview（报告及本次统计），移除单例上的 last 状态。
- ReflectionOrchestrator 只编排复核和修订，CriticAgent 执行质检，CriticPromptBuilder 构造质检提示词；修订返回结构化报告。
- ToolRegistry 负责工具定义与分发，RepositorySearchService 负责搜索缓存/限流，GithubCodeSearcher 负责搜索 HTTP。
- Message、Tool、ToolCall 保留数据职责；OpenAiMessageMapper 负责供应商协议转换和 JSON 编码。
- PrUrl 保留值对象职责，GitHub API 路径由 GitHub 适配包生成。
- 其他类逐项记录职责；不把无持久化项目硬套 DAO 层，不因类长就拆分纯粹的单一算法。

## 执行与验证

- [x] 基线：运行 mvn test；阅读所有生产类及受影响测试。
- [x] 回归：先证明修订 keyFindings 丢失和工具参数转义失败；补充 Agent 请求统计隔离测试。
- [x] 重构解析、合并与 Agent 结果；把原 Pipeline 解析测试迁到解析器边界。
- [x] 重构复核、工具搜索、协议转换与 HTTP 错误边界；迁移相关构造测试。
- [x] 运行完整后端测试、检查差异，补充分层依赖约束测试及逐类审查报告。
- [x] 更新架构说明与上一轮 HTML 中受此次改动影响的内容。

## 范围边界

不改变风险规则语义、分页能力、提示词审查标准、模型调用上限、异步能力和外部 API。格式解析统一会保留修订中的 keyFindings；协议编码使用 Jackson 修复合法字符串被错误拼接的问题；统计改为请求级返回。

独立审查补充：批量上下文策略抽到 RiskFileContextLoader；Critic 空白回复进入格式恢复；搜索 HTTP 失败不再缓存为无结果。最终 `mvn clean test`：162 个测试通过。
