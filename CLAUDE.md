# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

### Backend (Spring Boot 3, Java 17)

```bash
cd backend
mvn spring-boot:run          # start on http://localhost:8080
mvn test                     # run all tests
mvn test -Dtest=ClassName    # run a single test class
mvn package -DskipTests      # build fat jar
```

### Frontend (Vue 3 + Vite)

```bash
cd frontend
npm install
npm run dev     # dev server on http://localhost:5173 (proxies /api/* → :8080)
npm run build   # production build to frontend/dist/
```

### Required environment variables

```bash
export GITHUB_TOKEN=ghp_...       # optional but raises rate limit from 60 to 5000 req/h
export DEEPSEEK_API_KEY=sk-...    # required for /api/review; missing key → 401 at call time
```

## Architecture (V3 ReAct Agent)

The backend is a Spring Boot service where `POST /api/review` flows through:

1. **Preprocessing** (`GithubPrFetcher` → `DiffParser` → `FileClassifier` → `RiskDetector` → `ContextLoader`) — deterministic stages that fetch PR data, classify files, and run 9 heuristic risk rules.
2. **ReAct Agent Loop** (`ReviewAgent`) — replaces the old single-shot LLM call. The LLM receives the diff + rule results + a list of available tools. It autonomously decides: fetch more file content? search the repo? When satisfied, it outputs the final review JSON. Loop bounded by `max-rounds` (default 8) and converges after 4 tool calls.
3. **Tool Registry** (`ToolRegistry`) — two tools available to the LLM: `fetch_file_content(path)` and `search_repo(query)`. Thread-safe singleton; `search_repo` rate-limited at 25 calls/min with per-query caching.
4. **Reflection Loop** (`ReflectionOrchestrator`) — CriticAgent checks the review for HALLUCINATION / MISSING / SEVERITY / DUPLICATE / CONSISTENCY. Issues trigger one revision round. Critic parse failures retry once then log ERROR (not silently swallowed).
5. **Self-Correction** — `ReviewReplyReader.read()` shared by initial review and revision: JSON parse failure → error feedback to LLM → one retry.

### Key components

| Class | Package | Role |
|---|---|---|
| `ReviewAgent` | `service.ai` | ReAct loop: messages → chat() → parse tool_calls → execute → loop |
| `ToolRegistry` | `service.ai` | Tool dispatch; RepositorySearchService owns rate limiting/caching |
| `ReflectionOrchestrator` | `service.critic` | Critic → Revision quality loop |
| `ModelProvider` | `service.ai` | 无端点状态的协议端口：`complete(ep,…)` (单发) + `chat(ep,…)` (多轮带工具) |
| `OpenAiCompatibleProvider` | `service.ai` | OpenAI 兼容 `/v1/chat/completions` 实现；覆盖所有同类厂商 |
| `ModelEndpoint` | `service.ai` | 具名端点值对象（baseUrl/apiKey/model/timeout…）；经调用入参传入 |
| `ModelEndpointResolver` / `ConfigModelEndpointResolver` | `service.ai` | 端点来源接缝（F10）；本次从 `AiProperties` 解析生效端点 |
| `PromptBuilder` | `service.prompt` | System prompt (8 Reviewer Rules + tool-use instructions) + user prompt |
| `Message` / `ToolCall` / `AgentResponse` / `Tool` | `service.ai` | ReAct data model records |
| `JsonReplyCleaner` | `service.ai` | Shared fence-stripping / JSON extraction |

### Data flow

```
PrUrl
  → RepoAllowlist.requireAllowed()   ← guard before any GitHub IO
  → GithubPrFetcher (fetch PR files + title + head ref)
  → DiffParser → FileClassifier → RiskDetector (9 rules) → ContextLoader
  → ReviewAgent.review()          ← ReAct loop (LLM + tools), gated by Deadline
  → ReflectionOrchestrator.refine() ← Critic → optional Revision
  → mergeRisks(ruleRisks, aiRisks) → ReviewResult
```

### Risk rules (9 total, auto-discovered via `List<RiskRule>` injection)

`service/risk/rules/`: UnreleasedLock, NestedTransaction, BareCatch, ExceptionSwallowing, SqlConcatenation, HardcodedSecret, InsecureRandom, WeakHash, SystemOutPrintln.

Add a rule: drop a `@Component` implementing `RiskRule` into that package — no registry changes needed.

## Cross-cutting invariants

These span multiple files and are enforced by tests or config, not obvious from any single class:

- **Layer dependency direction is test-enforced.** `architecture/LayerBoundariesTest` reads compiled bytecode in `target/classes` (via ASM) and **fails `mvn test`** on any upward call: `model/*` → service/pipeline/controller, `service/*` → controller/pipeline, `pipeline/*` → controller **or Jackson **`com.fasterxml.jackson/*`** or Spring Web **`org.springframework.web/*`**. The pipeline layer must stay web- and JSON-library-free. Run `mvn test` after any package move or new cross-layer call.
- **Whole-request budget (`Deadline`).** `agent.request-budget-ms` (default 45000) is a single wall-clock budget created per request and checked before each ReAct round, each tool call, and before reflection — it bounds how many model round-trips may *start*, not total elapsed time (an in-flight call runs to its own timeout). Pass the `Deadline` through; don't reintroduce per-layer-only timeouts.
- **Repository allowlist (confused deputy).** Every repo has a server-side token, but callers pick the repo. `RepoAllowlist` (guarded in `GithubPrFetcher`) enforces `github.allowed-repos` (`owner/repo`, `*` wildcard). Empty = unrestricted (localhost/demo default); the constructor logs a WARN when a token is set with no allowlist.

## Configuration

`backend/src/main/resources/application.yml`:

```yaml
reviewpilot:
  prompt.budget.{max-patch-chars-per-file: 6000, max-total-chars: 60000}
  github.{api-base, token, allowed-repos, timeout, max-file-pages}
  ai.deepseek.{api-base, api-key, model, timeout, max-tokens, temperature, max-retries}
  agent:
    request-budget-ms: 45000              # whole-request Deadline (see above)
    reflection.enabled: true              # Critic→Revision loop; false = single pass
    content-fetcher.{enabled, max-prefetch-files}
    tools.{search-limit-per-minute, search-cache-entries}
    react.{max-rounds, max-total-chars}
```

**Gotcha:** `agent.*` keys must sit directly under `reviewpilot`, not under `ai` — the `@Value` expressions read `reviewpilot.agent.*`. Nesting them under `ai:` makes them silently inert (this is how `reflection.enabled=false` once stopped working).

## API endpoints

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/api/health` | Liveness check |
| GET | `/api/pr/files?prUrl=<url>` | Fetch + parse diff only (no LLM) |
| POST | `/api/review` | Full pipeline: ReAct agent review + Critic/Reflection |

Error codes: `400` bad URL, `401` auth failure or missing key, `403` repo outside allowlist, `404` PR not found/private, `502` DeepSeek or GitHub upstream failed.

## Java responsibility boundaries (2026-09-07)

See `docs/java-layering-review.md` for the full class inventory. Controllers call application use cases (`ReviewPipeline`, `PrFilesQuery`); `ApiExceptionHandler` maps errors by type. Keep review JSON recovery in `ReviewReplyReader`, risk merging in `RiskMerger`, provider wire conversion in `OpenAiMessageMapper`. `ReviewAgent.review` returns `AgentReview` with per-invocation statistics; never restore singleton last-request fields. Reflection returns a structured revision and delegates prompts to `CriticPromptBuilder` and inspection to `CriticAgent`. `RiskFileContextLoader` owns optional batch enrichment/truncation; `FileContentFetcher` owns GitHub IO. `GithubCodeSearcher` propagates failures so search policy cannot cache them as misses.
