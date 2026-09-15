# 多厂商模型层 验收清单

> 每一项通过运行命令或观察行为来验证，聚焦系统行为而非实现细节。所有后端命令在 `backend/` 目录下执行。密钥类断言可用本地 MockWebServer / 假 key 验证，无需真实厂商额度。

## 实现完整性
- [ ] 声明多个具名端点并指定 `active`，服务能成功启动、不报冲突（验证：写含 2 个 `endpoints.*` + `active` 的配置，`mvn spring-boot:run` 起来，`GET /api/health` 返回 200）—— AC1
- [ ] 只声明一个端点、不设 `active`，该端点被自动采用（验证：`AiPropertiesTest` 相应用例通过；或 `mvn spring-boot:run` 后评审接口不报「无生效端点」）—— AC2
- [ ] 声明多个端点但都不设 `active`，启动失败且原因明确（验证：`mvn spring-boot:run` 崩溃日志含「multiple endpoints declared but reviewpilot.ai.active not set」类字样，非泛化的 bean 创建失败）—— AC2
- [ ] `active` 指向一个未声明的端点名 → 启动失败且提示可用端点名（验证：设 `active: nope`，`mvn spring-boot:run` 启动即报可定位的配置错误）—— AC5
- [ ] 端点缺关键字段（如 api-base 为空）→ 启动失败并定位到该端点名（验证：删掉某端点 `api-base`，启动报含该端点名的错误）—— AC5
- [ ] 存在「端点来源」单一接缝：换生效端点只改 `active` 一个词、不改代码即可切换（验证：`active` 从 `deepseek` 改到 `mock` 指向 MockWebServer，不改 Java 代码即可发起评审并发往新地址）—— AC8/F10

## 集成
- [ ] 评审全链路（ReAct 探索 + Critic 质检/修订）的模型请求都发往生效端点地址（验证：把 `active` 端点 base-url 指向 MockWebServer，触发 `/api/review`，捕获到的请求数 = 探索轮 + 质检/修订调用，且全部命中该地址）—— AC3/F4
- [ ] 调用方与「端点来源」解耦：provider 不读配置、不持有单一焊死客户端（验证：代码走查——`OpenAiCompatibleProvider` 无 `@Value`/`AiProperties` 依赖、`chat/complete` 首参为 `ModelEndpoint`；`ReviewPipeline` 仅经 `resolver.active()` 取端点）—— AC10/F10
- [ ] 结果 meta 报告生效端点名与模型标识，且与实际配置一致（验证：`/api/review` 响应 `meta.provider`=`active` 端点名、`meta.model`=该端点 model 字段；前端 MetaFooter 显示「端点名 / 模型」）—— AC4/F5
- [ ] 新增一家 OpenAI 兼容厂商零代码（验证：仅加一段 `endpoints.<新名>` + 改 `active`，指向本地模拟的 OpenAI 兼容服务，得到结构化评审结果，diff 中无任何 provider 代码改动）—— AC8/F9/N1

## 编译与测试
- [ ] 后端全量编译通过（验证：`mvn -q compile` 无错误）
- [ ] 全部测试通过（验证：`mvn -q test` 全绿）—— AC11
- [ ] 分层依赖约束未破坏（验证：`mvn -q test -Dtest=LayerBoundariesTest` 通过——service/pipeline 无反向依赖、pipeline 不引入 Jackson/Spring Web）—— AC11/N3
- [ ] 旧 provider 痕迹清除（验证：`grep -rn "DeepSeekProperties|deepSeekWebClient|modelName()" backend/src/main` 无结果；仓库配置中无 `reviewpilot.ai.deepseek.` 段）—— N4

## 安全（密钥）
- [ ] 配置与代码无明文密钥（验证：`grep -rniE "(sk-|ghp_)[a-z0-9]{10,}" backend/src/main frontend/src` 无结果；yml 里 api-key 均为 `${ENV:}` 占位）—— AC6/F7
- [ ] 日志只出现脱敏 key（验证：启动 banner 与调用错误日志中 key 呈 `****<后4位>`；`ModelEndpoint.redactedKey()` 单测覆盖空/短/常规三种）—— AC6/F7
- [ ] 缺 key 优雅降级（验证：`active` 端点 `DEEPSEEK_API_KEY` 留空启动 → `/api/health` 200、`/api/pr/files` 正常、`/api/review` 返回 401 且信息含「api key is not set」、进程不崩溃退出）—— AC7/F8

## 端到端场景
- [ ] 场景 1（默认本地跑通）：clone 仓库 → 仅 `export DEEPSEEK_API_KEY=...` → `mvn spring-boot:run` → 前端输入一个公开 PR URL 点 Analyze → 返回 Summary/Risks/Suggestions，MetaFooter 显示 `deepseek / deepseek-chat`（验证：真实浏览器或 `curl POST /api/review` 观察响应）—— AC9/N4
- [ ] 场景 2（切厂商）：配置加一个指向 MockWebServer 的 `mock` 端点并 `active: mock` → 重启 → 同一 PR URL 发起评审 → MockWebServer 记录到探索+质检请求、前端正常渲染结果（验证：观察 MockWebServer 请求日志与前端结果）—— AC3/AC8
- [ ] 场景 3（边界·配置错误）：`active` 填一个不存在的端点名 → 启动即失败、错误信息可读且定位到该端点（验证：`mvn spring-boot:run` 启动日志）—— AC5
