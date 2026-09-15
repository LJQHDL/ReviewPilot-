# 多厂商模型层 技术设计

> 语言：Java（Spring Boot 3）。本文满足 spec 的 F1–F10、N1–N4。

## 架构概览

当前：一个 `DeepSeekProperties` 记录 + 一个启动期焊死 base-url/密钥的 `WebClient` bean + 一个 `DeepSeekProvider` 单例，三者强绑 DeepSeek。

改造为四部分，职责清晰分层：

1. **`ModelEndpoint`（值对象）** —— 一个端点的全部描述：名字、base-url、apiKey、model、timeout、max-tokens、temperature、max-retries。它是「发起一次调用所需的端点信息」的载体，也是 F10 扩展口的数据类型。
2. **`AiProperties`（配置绑定）** —— 绑定 `reviewpilot.ai.*`：`active`（生效端点名）+ `endpoints.<name>`（多套具名端点）。负责解析、默认值与**启动期校验**（F6）。**不做旧 `deepseek.*` 段的向后兼容**（N4：个人项目无存量用户，仓库自带新结构 yml 保证本地开箱可跑）。
3. **`ModelEndpointResolver`（端口来源）** —— 对上层暴露单一方法 `ModelEndpoint active()`。本次实现从 `AiProperties` 取生效端点。**这就是 F10 的「填充来源」接缝**：将来把来源换成「登录用户 / 每请求注入」时，只替换这个实现，调用方与 provider 都不动。
4. **`OpenAiCompatibleProvider`（协议执行器，无端点状态）** —— 实现既有 `ModelProvider` 接口。持有一个**不带 base-url、不带默认鉴权头**的通用 `WebClient.Builder`；每次调用时从传入的 `ModelEndpoint` 现场拼出地址、Bearer 头、超时与重试。协议仍走 OpenAI 兼容（复用 `OpenAiMessageMapper`），故 F9「加同类厂商零代码」成立。

调用链上层（`ReviewAgent` / `CriticAgent` / `ReflectionOrchestrator`）与 `ReviewPipeline` 不再持有「某一个 provider 单例的内部端点」，而是在**一次评审开始时向 resolver 取一次 `ModelEndpoint`**，随调用逐层传入 provider。这满足「不做请求内多厂商」——一个请求内端点恒定——同时把「端点从哪来」这件事收敛到 resolver 一个点。

## 核心数据结构

### `ModelEndpoint`（record，service.ai）
```java
record ModelEndpoint(
    String name,          // 具名端点名，进入结果 meta.provider（F5）
    String baseUrl,       // OpenAI 兼容服务根地址，如 https://api.deepseek.com
    String apiKey,        // Bearer token；空串表示未配置（F8 据此降级）
    String model,         // 模型标识，如 deepseek-chat；进入 meta.model（F5）
    Duration timeout,     // 单次读超时（N3）
    int maxTokens,
    double temperature,
    int maxRetries        // 瞬时网络错误重试次数（N3）
) { boolean isConfigured() { return !apiKey.isBlank(); } }
```

### `AiProperties`（@ConfigurationProperties("reviewpilot.ai")，config）
```java
record AiProperties(
    String active,                     // 生效端点名；可空
    Map<String, EndpointSpec> endpoints
) {
    record EndpointSpec(               // 单套端点的原始配置（字段可空，带默认）
        String apiBase, String apiKey, String model,
        Duration timeout, Integer maxTokens, Double temperature, Integer maxRetries
    ) {}
}
```
紧凑构造器负责：填默认值；执行 F3/F6 校验（active 缺省时唯一端点自动生效、多端点无 active 报错、active 指向不存在或字段缺失报错），校验失败抛 `IllegalStateException` 使启动失败。解析后产出 `ModelEndpoint resolveActive()`。**无旧配置归一化逻辑**——只认 `endpoints.<name>.*` 这一种结构。

### `ModelProvider`（既有接口，签名扩展 —— F10）
```java
interface ModelProvider {
    String name();                                   // provider 协议名，如 "openai-compatible"
    AgentResponse chat(ModelEndpoint ep, List<Message> messages, List<Tool> tools);
    String        complete(ModelEndpoint ep, String systemPrompt, String userPrompt);
}
```
`chat`/`complete` 新增首参 `ModelEndpoint`。`modelName()` 移除（模型名改从 `ep.model()` 取，供 `Meta` 使用）。这是唯一破坏性的接口变更，波及 4 个调用点，改动见「模块交互」。

### `ModelEndpointResolver`（新接口，service.ai）
```java
interface ModelEndpointResolver { ModelEndpoint active(); }
```
`ConfigModelEndpointResolver`（`@Component`）委托 `AiProperties.resolveActive()`。**本次到此为止：resolver 只读配置。** 子项目②将来新增一个从「请求上下文/用户凭证」取端点的实现并换掉注入，即完成运行时密钥注入，其余代码不动。

## 模块设计

### config：`AiProperties`
- **职责：** 绑定 + 默认值 + 启动期校验（F1,F2,F3,F6,F7 的「配置」面）。
- **对外接口：** `resolveActive() : ModelEndpoint`。
- **依赖：** 无（纯配置）。
- 移除 `DeepSeekProperties`；`apiKey` 一律来自 yml 里的 `${SOME_ENV:}` 占位，绝不落明文（F7）。

### config：通用 `WebClient`
- 原 `deepSeekWebClient`（带 base-url + 默认 Authorization）改为 `openAiWebClientBuilder`（`WebClient.Builder`，仅设连接/缓冲/UA，**不设 base-url、不设鉴权头**）。超时/鉴权/地址全部下放到每次调用按 `ModelEndpoint` 现拼。

### service.ai：`OpenAiCompatibleProvider implements ModelProvider`
- **职责：** 由 `DeepSeekProvider` 泛化而来；给定 `ModelEndpoint`+消息，发 OpenAI 兼容请求、解析响应（复用 `OpenAiMessageMapper`、`DsCompletion` 等 DTO 与 `callWithRetry` 重试逻辑）。
- **对外接口：** `chat(ep, …)` / `complete(ep, …)`；未配置（`!ep.isConfigured()`）抛 `AiAuthenticationException`（F8）。
- **依赖：** `WebClient.Builder`、`ObjectMapper`。`name()` 返回 `"openai-compatible"`。
- 日志：仅 `ep.name()`/`ep.model()`/脱敏 key（`****<last4>`），沿用 `redactedKey` 逻辑搬到 `ModelEndpoint` 或 provider 私有方法（F7）。

### service.ai：`ConfigModelEndpointResolver implements ModelEndpointResolver`
- **职责：** `active()` → `aiProperties.resolveActive()`。
- **依赖：** `AiProperties`。

### service.ai：调用方（`ReviewAgent`、`CriticAgent`、`ReflectionOrchestrator`）
- 构造注入的 `ModelProvider` 保留；但**新增/改为在方法入参接收 `ModelEndpoint`**，转发给 `modelProvider.chat(ep, …)` / `complete(ep, …)`。`ReviewPipeline` 在 `review()` 开头 `ModelEndpoint ep = resolver.active()` 取一次，向下传递。

### pipeline：`ReviewPipeline`
- 注入 `ModelEndpointResolver`。`review()` 起始处解析一次 `ep`，贯穿 agent/critic。`Meta` 的 provider 字段用 `ep.name()`、model 用 `ep.model()`（替代原 `modelProvider.name()/modelName()`，F5）。

## 模块交互

```
ReviewPipeline.review(prUrl)
  ├─ ModelEndpoint ep = resolver.active()        ← 唯一的「端点来源」调用点（F10 接缝）
  ├─ ReviewAgent.review(files, …, ep, deadline)
  │     └─ provider.chat(ep, messages, tools)     ← 每次 ReAct 轮
  ├─ ReflectionOrchestrator.refine(…, ep)
  │     └─ CriticAgent → provider.complete(ep, …) ← 质检/修订
  └─ Meta(ep.name(), ep.model(), …)               ← 结果报告厂商（F5）
```
换厂商：改 yml 的 `active` 或端点定义 → 重启。用户自带 key（未来）：把 `resolver.active()` 换成「按当前请求取凭证」的实现，**上面整条链一行不改**。

## 文件组织

```
backend/src/main/java/com/reviewpilot/
├── config/
│   ├── AiProperties.java            【新建】绑定 reviewpilot.ai.*、默认值、兼容归一化、启动校验
│   ├── DeepSeekProperties.java      【删除】能力并入 AiProperties
│   ├── DeepSeekClientConfig.java    【改造】→ 产出通用 openAiWebClientBuilder（无 base-url/无鉴权头）
│   └── OpenAiClientConfig.java      【新建，或由上者改名】
├── service/ai/
│   ├── ModelEndpoint.java           【新建】端点值对象
│   ├── ModelProvider.java           【改造】chat/complete 增加 ModelEndpoint 入参，移除 modelName()
│   ├── OpenAiCompatibleProvider.java【改造/改名自 DeepSeekProvider】无端点状态的协议执行器
│   ├── ModelEndpointResolver.java   【新建】接口
│   ├── ConfigModelEndpointResolver.java【新建】@Component，读 AiProperties
│   ├── ReviewAgent.java             【改造】方法带 ep 入参，转发给 provider
│   ├── ReviewReplyReader.java       （不变）
│   └── OpenAiMessageMapper.java     （不变，复用）
├── service/critic/
│   ├── CriticAgent.java             【改造】complete(ep, …)
│   └── ReflectionOrchestrator.java  【改造】透传 ep
└── pipeline/ReviewPipeline.java     【改造】解析 ep 一次并下传；Meta 用 ep 的 name/model
```
测试：`DeepSeekProviderTest` → 更名/改写为 `OpenAiCompatibleProviderTest`（用 MockWebServer，端点参数化）；新增 `AiPropertiesTest`（F3 规则、F6 校验）；`ReviewPipeline*Test`/`ReviewAgentTest`/`Critic*` 适配新签名。

## 技术决策

| 决策点 | 选择 | 理由 |
|--------|------|------|
| provider 如何满足 F10「预留接口」 | `chat/complete` 增加 `ModelEndpoint` 入参，端点不再藏在 provider 内部 | 端点信息经出口传入、由 resolver 一个点填充；将来换成运行时注入只改 resolver，符合「调用方不返工」 |
| 端点解析放在哪 | `ReviewPipeline.review()` 起始处 `resolver.active()` 取一次，向下透传 | 满足「不做请求内多厂商」；单请求端点恒定；接缝集中、易替换 |
| provider 是否仍持 base-url | 否；改持通用 `WebClient.Builder`，按 ep 现拼地址/鉴权/超时 | 若仍焊死 base-url 就无法运行时切换，违背 F10 意图 |
| 配置结构 | `endpoints.<name>.*` 多套 map + `active` 开关 | 满足 F1/F2；一个 map 天然支持任意数量端点 |
| 向后兼容 | 不做；只认新结构 `endpoints.<name>.*`，仓库自带一份 DeepSeek 默认端点配置 | 个人项目无存量用户（N4）；省去归一化分支，`AiProperties` 更简单 |
| 多端点未指定 active | 启动失败（抛异常），不猜默认 | F3 明确「宁可失败也不猜」，避免误用错 key/厂商 |
| 是否引入原生协议适配器 | 不；仅 OpenAI 兼容 | spec「不做的事」；F9 已覆盖绝大多数网关 |
| provider 命名 | `DeepSeekProvider` → `OpenAiCompatibleProvider` | 名实相符：协议是 OpenAI 兼容，不专属 DeepSeek |
