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
5. **Self-Correction** — `parseWithRetry()` in `ReviewAgent`: JSON parse failure → error feedback to LLM → one retry.

### Key components

| Class | Package | Role |
|---|---|---|
| `ReviewAgent` | `service.ai` | ReAct loop: messages → chat() → parse tool_calls → execute → loop |
| `ToolRegistry` | `service.ai` | Tool dispatch, rate limiting, caching |
| `ReflectionOrchestrator` | `service.critic` | Critic → Revision quality loop |
| `ModelProvider` | `service.ai` | `complete()` (single-shot) + `chat()` (multi-turn with tools) |
| `DeepSeekProvider` | `service.ai` | OpenAI-compatible `/v1/chat/completions` with `tools` field |
| `PromptBuilder` | `service.prompt` | System prompt (8 Reviewer Rules + tool-use instructions) + user prompt |
| `Message` / `ToolCall` / `AgentResponse` / `Tool` | `service.ai` | ReAct data model records |
| `JsonReplyCleaner` | `service.ai` | Shared fence-stripping / JSON extraction |

### Data flow

```
PrUrl
  → GithubPrFetcher (fetch PR files + title + head ref)
  → DiffParser → FileClassifier → RiskDetector (9 rules) → ContextLoader
  → ReviewAgent.review()          ← ReAct loop (LLM + tools)
  → ReflectionOrchestrator.refine() ← Critic → optional Revision
  → mergeRisks(ruleRisks, aiRisks) → ReviewResult
```

### Risk rules (9 total, auto-discovered via `List<RiskRule>` injection)

`service/risk/rules/`: UnreleasedLock, NestedTransaction, BareCatch, ExceptionSwallowing, SqlConcatenation, HardcodedSecret, InsecureRandom, WeakHash, SystemOutPrintln.

Add a rule: drop a `@Component` implementing `RiskRule` into that package — no registry changes needed.

## Configuration

`backend/src/main/resources/application.yml`:

```yaml
reviewpilot:
  prompt.budget.max-patch-chars-per-file: 6000
  prompt.budget.max-total-chars: 60000
  agent.reflection.enabled: true
  agent.content-fetcher.enabled: true
  agent.react.max-rounds: 8
  agent.react.max-total-chars: 64000
  ai.deepseek.{api-base,api-key,model,timeout,max-tokens,temperature}
```

## API endpoints

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/api/health` | Liveness check |
| GET | `/api/pr/files?prUrl=<url>` | Fetch + parse diff only (no LLM) |
| POST | `/api/review` | Full pipeline: ReAct agent review + Critic/Reflection |

Error codes: `400` bad URL, `401` auth failure or missing key, `404` PR not found/private, `502` DeepSeek call failed.
