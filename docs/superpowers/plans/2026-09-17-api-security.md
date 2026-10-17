# API security implementation plan

Goal: implement the four security measures approved in the conversation: API authentication, production repository restrictions, request admission limits, and safe error reporting.

Architecture: a servlet filter authenticates API requests using a dedicated Bearer token, then admits review requests through a single-instance rate/concurrency gate. Existing pipeline allowlist checks remain authoritative. Production startup rejects unsafe configuration. Vue collects the access token at runtime without persistent storage.

Constraints: Java 17, existing Spring MVC/Vue stack, no new infrastructure or dependencies; preserve existing uncommitted model changes. This release uses one shared service identity, not per-user repository permissions. Production deployment requires HTTPS. Default development binds to loopback; production must explicitly enable authentication.

## Tasks

- [x] Add HTTP integration tests for unauthorized review/files requests, valid token, public health, preflight, excess review traffic, permit release, and sanitized errors. Run these against existing code to establish failures.
- [x] Add `config/ApiSecurityFilter.java` for authentication and bounded admission; test concurrent requests with latches and rate-window renewal with a supplied clock. Configuration defaults: 10 reviews/minute, 2 concurrent reviews. Return JSON errors, 401 Bearer challenge, 429 Retry-After, and Cache-Control: no-store.
- [x] Add `config/ProductionSecurityConfig.java` startup checks for prod/production profiles: authentication must be enabled, token must be at least 32 printable non-space ASCII characters, and repository entries must be explicit owner/repo or owner/* (no global owner wildcard). Test with ApplicationContextRunner.
- [x] Remove upstream bodies and exception messages from provider/tool error logs and responses; preserve intentional client errors and framework status codes. Add regression tests with secret markers.
- [x] Wire runtime token entry into `frontend/src/App.vue` and `frontend/src/api/review.js`, ensuring the token is cleared on refresh and never built into assets or written to browser storage. Stop automatic deep-link reviews; require explicit submission.
- [x] Document environment settings, HTTPS, single-instance/shared-identity limitations, and run `mvn test`, `npm run build`, frontend request tests and diff checks.

## Acceptance

Unauthenticated and over-quota requests never invoke the pipeline. Existing pipeline tests verify repository rejection before fetch. Production startup fails closed. Concurrent failures release permits. Upstream secret markers appear in neither returned JSON/tool text nor captured logs. Existing model/API tests and frontend build pass.

Evidence: `mvn test` passed 202 tests (0 failures/errors/skips); `node --test src/api/review.test.js` passed; `npm.cmd run build` passed; `git diff --check` passed. Browser checks used intercepted API responses: explicit deep-link submission, 401 opens settings, runtime Authorization header, no token in local/session storage, clear/refresh removes token, and no overflow at 390px width. No paid API calls or deployment performed.
