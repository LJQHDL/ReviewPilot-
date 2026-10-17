# ReviewPilot 安全部署

本轮实现应用访问鉴权、生产仓库范围校验、审查入口限流/并发保护和上游错误脱敏。

## 本地开发

默认后端仅监听 `127.0.0.1:8080`，鉴权关闭。前端仍通过 Vite `/api` 代理访问。
需要在本地验证鉴权时设置 `REVIEWPILOT_SECURITY_ENABLED=true` 和独立访问令牌。
审查请求默认每分钟最多 10 次、同时最多 2 个；即使关闭鉴权也保留准入限制。

## 生产启动

启动进程前，通过部署平台的秘密配置设置以下环境变量，勿把实际秘密写入仓库或命令历史：

| 变量 | 要求 |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | `prod`（也支持 `production`） |
| `REVIEWPILOT_SECURITY_ENABLED` | `true` |
| `REVIEWPILOT_ACCESS_TOKEN` | 独立随机访问令牌，32–256 个无空白 ASCII 可打印字符；建议使用随机生成的 32 字节十六进制值 |
| `REVIEWPILOT_ALLOWED_REPOS` | 逗号分隔，例如 `my-org/service-a,my-org/service-b`；支持 `my-org/*`，不接受全局所有者通配符 |
| `GITHUB_TOKEN` | 仅授予必要仓库的只读权限 |
| `DEEPSEEK_API_KEY` | 模型服务密钥 |
| `SERVER_ADDRESS` | 默认 `127.0.0.1`；容器确需外部监听时设为 `0.0.0.0`，并限制后端端口访问 |

缺少启用的鉴权、有效访问令牌或有效仓库范围时，生产 profile 启动失败。
生产必须显式启用上述 profile；不能自动识别机器是否属于生产环境。
通过 HTTPS 反向代理提供页面和 `/api`，不把 Bearer 令牌通过公网明文 HTTP 传输。
同源部署无需跨域配置；分域时设置 `reviewpilot.web.cors.allowed-origins` 为确切的前端地址。

打开页面的「访问设置」，输入 ReviewPilot 访问令牌，再提交 PR 地址。它只存于页面内存，刷新后需重填，点击清除可移除。
这里不能填 GitHub Token 或模型密钥。分享链接只填入 PR 地址，不再自动发起审查。

## 接口约定

- 开启鉴权后，除 `GET /api/health` 和由 CORS 处理的预检外，请求必须带 `Authorization: Bearer <访问令牌>`。
- 缺失、错误、重复 Authorization 头返回 `401`、`WWW-Authenticate: Bearer` 和 `{"error":"Authentication required"}`。
- 全部 POST 使用共享审查准入计数；超过频率或并发上限返回 `429` 与 `Retry-After` 秒数，不进入业务层。
- 阻止的请求不消耗审查额度；进入业务层后失败的请求计入频率，且在结束时释放并发名额。
- 成功和错误响应禁止缓存；上游错误不回传原始异常文本。框架自身的 400/404/405 语义保留。
- GitHub 和 AI 上游鉴权错误暂保留原有 401 状态，但只有应用鉴权失败带 Bearer challenge。

## 配额与边界

可配置 `reviewpilot.security.reviews-per-minute`（默认 10）和 `reviewpilot.security.max-concurrent-reviews`（默认 2），两者必须为正数。
频率使用固定 60 秒窗口，窗口边界可能连续放行两批请求；并发限制持续有效。重启会重置计数。
这是单实例、共享身份保护；所有令牌持有者拥有相同仓库范围和额度，不提供用户隔离。不要把服务令牌公开分发。
多实例部署需在网关统一限流；新增异步审查时必须重新设计并发名额生命周期。
应用层只对 `POST /api/review` 计审查额度；`GET /api/pr/files` 不受应用层限流，但仍受鉴权和仓库白名单保护，生产网关应统一限制请求速率和请求体大小。非 `/api/*` 路径不经过鉴权过滤器。
请求时间预算耗尽后不再发起任何新的模型调用（包括强制收尾与解析修复）；已经发出的请求仍运行到自身超时。本轮未增加硬取消、每日费用配额或代码内容脱敏。

## 验证

在 backend 执行 `mvn test`，覆盖无令牌拒绝、合法令牌、跨域错误可读、仓库范围、限流、并发回收和错误脱敏。
在 frontend 执行 `node --test src/api/review.test.js` 与 `npm run build`（Windows 可用 `npm.cmd run build`）。
这些验证不需要真实 GitHub/模型密钥，不会发起付费审查。
