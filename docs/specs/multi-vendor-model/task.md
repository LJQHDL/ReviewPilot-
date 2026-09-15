# 多厂商模型层 任务拆解

> 依赖 plan.md 的接口定义。所有「验证」以 `cd backend && mvn -q ...` 为准。测试适配类的编译错误会连带触发，故签名改动（T4）之后紧跟调用方改造（T5–T8），最后统一 `mvn test` 收尾。

## 文件清单

| 操作 | 文件 | 职责 |
|------|------|------|
| 新建 | `service/ai/ModelEndpoint.java` | 端点值对象（F10 数据类型） |
| 新建 | `config/AiProperties.java` | 绑定 `reviewpilot.ai.*`、默认值、启动校验 |
| 新建 | `service/ai/ModelEndpointResolver.java` | 端点来源接口（F10 接缝） |
| 新建 | `service/ai/ConfigModelEndpointResolver.java` | 从 AiProperties 取生效端点的实现 |
| 新建 | `config/OpenAiClientConfig.java` | 产出通用 `WebClient.Builder`（无 base-url/无鉴权头） |
| 改名 | `service/ai/DeepSeekProvider.java` → `OpenAiCompatibleProvider.java` | 无端点状态的 OpenAI 兼容执行器 |
| 改造 | `service/ai/ModelProvider.java` | chat/complete 增 `ModelEndpoint` 入参，移除 `modelName()` |
| 改造 | `service/ai/ReviewAgent.java` | 方法带 ep 入参并透传给 provider |
| 改造 | `service/critic/CriticAgent.java` | `complete(ep, …)` |
| 改造 | `service/critic/ReflectionOrchestrator.java` | 透传 ep |
| 改造 | `pipeline/ReviewPipeline.java` | 注入 resolver、解析 ep 一次、下传；Meta 用 ep.name/model |
| 改造 | `resources/application.yml` | `ai.*` 换成 `active` + `endpoints.<name>` 新结构 |
| 删除 | `config/DeepSeekProperties.java` | 能力并入 AiProperties |
| 删除 | `config/DeepSeekClientConfig.java` | 由 OpenAiClientConfig 取代 |
| 改名 | `test/.../DeepSeekProviderTest.java` → `OpenAiCompatibleProviderTest.java` | 端点参数化契约测试 |
| 新建 | `test/.../config/AiPropertiesTest.java` | F3 规则 + F6 校验单测 |
| 改造 | `test/.../{ReviewAgentTest,ReviewPipelineFlowTest,ReviewPipelineIntegrationTest,ReflectionOrchestrator*Test}` | 适配新签名 |

## T1: `ModelEndpoint` 值对象

**文件：** `service/ai/ModelEndpoint.java`
**依赖：** 无
**步骤：**
1. 定义 record `ModelEndpoint(String name, String baseUrl, String apiKey, String model, Duration timeout, int maxTokens, double temperature, int maxRetries)`。
2. 加 `boolean isConfigured()`：`apiKey != null && !apiKey.isBlank()`。
3. 加 `String redactedKey()`：空→`(unset)`，≤4 位→`****`，否则 `****`+后 4 位（从旧 `DeepSeekProperties` 搬来）。

**验证：** `mvn -q compile` 通过，`ModelEndpoint` 可被同包引用。

## T2: `AiProperties` 配置绑定 + 启动校验

**文件：** `config/AiProperties.java`
**依赖：** T1
**步骤：**
1. `@ConfigurationProperties("reviewpilot.ai")` record，字段 `String active; Map<String, EndpointSpec> endpoints;`，内部 record `EndpointSpec(apiBase, apiKey, model, timeout, maxTokens, temperature, maxRetries)`。
2. `resolveActive() : ModelEndpoint`（F3）：endpoints 为空 → 抛 `IllegalStateException("no ai endpoint configured")`；`active` 非空 → 取该端点，缺失则抛异常并列出可用名；`active` 空且仅一个端点 → 用之；`active` 空且多个 → 抛异常「multiple endpoints declared but reviewpilot.ai.active not set」。
3. EndpointSpec → ModelEndpoint 的转换：各字段空则填默认（model 默认名取端点名，timeout=60s，maxTokens=4096，temperature=0.2，maxRetries=1；apiBase 无默认，缺失即 F6 报错）。
4. 校验 `apiBase` 非空，否则抛异常（定位到端点名）。

**验证：** 运行 `AiPropertiesTest`（T8 写），此任务先保证编译。

## T3: `AiPropertiesTest`（先立校验契约）

**文件：** `test/.../config/AiPropertiesTest.java`
**依赖：** T2
**步骤：**
1. 用例：单端点无 active → resolveActive 返回之（F3/AC2 前段）。
2. 用例：多端点无 active → 抛 IllegalStateException（F3/AC2 后段）。
3. 用例：active 指向未声明名 → 抛异常（F6/AC5）。
4. 用例：端点缺 apiBase → 抛异常（F6/AC5）。

**验证：** `mvn -q test -Dtest=AiPropertiesTest` 全绿。

## T4: `ModelProvider` 接口扩展

**文件：** `service/ai/ModelProvider.java`
**依赖：** T1
**步骤：**
1. `chat` 改为 `AgentResponse chat(ModelEndpoint ep, List<Message> messages, List<Tool> tools)`。
2. `complete` 改为 `String complete(ModelEndpoint ep, String systemPrompt, String userPrompt)`。
3. 移除 `default String modelName()`。
4. 更新类注释：`name()` 语义为「协议族名」（如 `openai-compatible`），厂商/模型展示改用 `ModelEndpoint`。

**验证：** 此步后 provider/调用方/测试会报未实现或参数不符（预期，由 T5–T9 修复）；单独 `mvn -q compile -pl` 无意义，跳过到 T9 统一验证。

## T5: `OpenAiCompatibleProvider`（由 DeepSeekProvider 泛化）

**文件：** `service/ai/DeepSeekProvider.java` → `OpenAiCompatibleProvider.java`
**依赖：** T1, T4
**步骤：**
1. 文件与类改名为 `OpenAiCompatibleProvider`；`implements ModelProvider`，`name()` 返回 `"openai-compatible"`。
2. 构造注入改为通用 `WebClient.Builder`（不再 `@Qualifier("deepSeekWebClient")`，不持有 `DeepSeekProperties`）。
3. `chat(ep, …)`/`complete(ep, …)`：入口处 `if (!ep.isConfigured()) throw new AiAuthenticationException("endpoint '"+ep.name()+"' api key is not set ...")`（F8）。
4. 请求体 `model/temperature/max_tokens` 取自 `ep`；地址由 `ep.baseUrl()+"/v1/chat/completions"` 现拼；每次调用 `builder.baseUrl(ep.baseUrl())` + 有 key 时加 `Authorization: Bearer ep.apiKey()` + 按 `ep.timeout()` 设 responseTimeout 的 WebClient。
5. `callWithRetry` 重试次数改用 `ep.maxRetries()`，其余逻辑不变（复用 `OpenAiMessageMapper`、`Ds*` DTO）。
6. 日志：`ep.name()`/`ep.model()`/`ep.redactedKey()`，绝不打印明文 key（F7）。

**验证：** `mvn -q compile` 通过（provider 自身，不含调用方）。

## T6: `ConfigModelEndpointResolver` + 接口

**文件：** `service/ai/ModelEndpointResolver.java`、`service/ai/ConfigModelEndpointResolver.java`
**依赖：** T2
**步骤：**
1. 新建接口 `ModelEndpointResolver { ModelEndpoint active(); }`（F10 接缝）。
2. 新建 `@Component ConfigModelEndpointResolver(AiProperties props)`，`active()` → `props.resolveActive()`。

**验证：** `mvn -q compile` 通过。

## T7: 调用方透传 ep（ReviewAgent / CriticAgent / ReflectionOrchestrator）

**文件：** `service/ai/ReviewAgent.java`、`service/critic/CriticAgent.java`、`service/critic/ReflectionOrchestrator.java`
**依赖：** T4, T5
**步骤：**
1. `ReviewAgent.review(...)` 参数列表加 `ModelEndpoint ep`；内部私有 `chat(...)`/`forceComplete(...)` 透传 ep，调用改 `modelProvider.chat(ep, messages, tools)`。
2. `CriticAgent`：`runCritic` 及调用点改 `modelProvider.complete(ep, …)`，ep 由 `refine`/构造参数链传入。
3. `ReflectionOrchestrator.refine(...)` 加 `ModelEndpoint ep` 参数，透传给 Critic 与 revision 的 `complete`。

**验证：** `mvn -q compile` 通过（不含 ReviewPipeline 调用处）。

## T8: `ReviewPipeline` 组装 + `Meta` 改源

**文件：** `pipeline/ReviewPipeline.java`
**依赖：** T6, T7
**步骤：**
1. 构造新增注入 `ModelEndpointResolver resolver`。
2. `review()` 起始（`allowlist.requireAllowed` 之后）`ModelEndpoint ep = resolver.active();`，向下传给 `reviewAgent.review(...ep...)` 与 `orchestrator.refine(...ep...)`。
3. 两处 `new ReviewResult.Meta(...)` 的 provider/model 参数改用 `ep.name()`/`ep.model()`（替代 `modelProvider.name()/modelName()`）（F5）。可移除对 `ModelProvider` 的直接字段依赖（若仅此处用到，删字段与构造参）。

**验证：** `mvn -q compile` 全模块通过（含 pipeline）。

## T9: `application.yml` 新结构 + 删除旧类

**文件：** `resources/application.yml`；删除 `config/DeepSeekProperties.java`、`config/DeepSeekClientConfig.java`；新建 `config/OpenAiClientConfig.java`
**依赖：** T5, T6
**步骤：**
1. 新建 `OpenAiClientConfig`：`@Bean WebClient.Builder openAiWebClientBuilder()`——设 connect 超时 10s、放大 buffer 到 4MB、Content-Type/UA，**不设 baseUrl/Authorization**（由各 provider 调用按 ep 现拼）。
2. yml：`reviewpilot.ai` 改为 `active: deepseek` + `endpoints.deepseek.{api-base, api-key: ${DEEPSEEK_API_KEY:}, model, timeout, max-tokens, temperature, max-retries}`；删掉旧 `provider:` 与 `deepseek:` 段（保留注释风格，说明 `endpoints.<name>` 用法与「active 缺省时唯一端点自动生效」）。
3. 删除 `DeepSeekProperties.java`、`DeepSeekClientConfig.java`。
4. `@EnableConfigurationProperties(AiProperties.class)` 加到某 config（或 OpenAiClientConfig），`@ConfigurationPropertiesScan` 若已在主类则无需。

**验证：** `mvn -q compile` 通过；grep `DeepSeekProperties|deepSeekWebClient` 于 main 源码应无残留。

## T10: 测试全面适配 + 端点参数化契约测试

**文件：** 改名 `OpenAiCompatibleProviderTest`；改 `ReviewAgentTest`、`ReviewPipelineFlowTest`、`ReviewPipelineIntegrationTest`、`ReflectionOrchestratorTest`、`ReflectionOrchestratorEvidenceTest`
**依赖：** T5, T7, T8
**步骤：**
1. `OpenAiCompatibleProviderTest`：MockWebServer 起服务，`ModelEndpoint` 参数化 baseUrl 指向它；断言请求头含 `Bearer <testkey>`、体含 `model`（F9/AC8 契约）。补一个「apiKey 空 → 抛 AiAuthenticationException」用例（F8/AC7）。
2. 其余测试：mock/匿名 `ModelProvider` 的方法签名补 `ModelEndpoint` 入参；`ReviewPipeline` 测试注入一个返回固定 ep 的 `ModelEndpointResolver`（可用 lambda 或 mock）。
3. `ReviewPipelineIntegrationTest` 断言 `meta.provider`/`meta.model` 等于所用 ep 的 name/model（AC4）。

**验证：** `mvn -q test` 全绿，含 `LayerBoundariesTest`（N3/AC11）。

## T11: 文档同步（CLAUDE.md / README 关键处）

**文件：** `CLAUDE.md`、`README.md`（密钥与配置章节）、`docs/prompt-strategy.md`（若提及 provider）
**依赖：** T9, T10
**步骤：**
1. CLAUDE.md 的 Configuration 段 `ai.deepseek.*` → `ai.active` + `ai.endpoints.<name>.*`；Key components 表里 DeepSeekProvider → OpenAiCompatibleProvider + 新增 resolver/ModelEndpoint 行；「预留运行时注入接缝」一句。
2. README 环境变量与配置示例改用新结构；端到端启动步骤保持「export DEEPSEEK_API_KEY → mvn spring-boot:run」。

**验证：** 人工核读；grep 文档内无残留 `reviewpilot.ai.deepseek.`（除历史 docs 记录）。

## 执行顺序

```
T1 ─┬─ T2 ─ T3
    ├─ T4 ─┬─ T5 ─┐
    │      ├─ T6  │
    │      └─ T7 ─┼─ T8 ─┐
    └─────────────┘       ├─ T10 ─ T11
                T5,T6 ─ T9┘
```
线性可执行序列（无并行时按此序）：**T1 → T2 → T3 → T4 → T5 → T6 → T7 → T8 → T9 → T10 → T11**。每个「验证」通过再进下一个；T4 会短暂破坏下游编译，到 T8/T9 恢复全模块 `compile`，T10 恢复全 `test`。
