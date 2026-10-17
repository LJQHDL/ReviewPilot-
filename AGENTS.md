# Repository Guidelines

## Project Structure & Module Organization

ReviewPilot is split into a Spring Boot backend and a Vue frontend. Backend source lives in `backend/src/main/java/com/reviewpilot`, organized by responsibility: `controller`, `model`, `config`, `pipeline`, and `service` subpackages such as `ai`, `github`, `risk`, `diff`, and `prompt`. Backend tests mirror the same package layout under `backend/src/test/java/com/reviewpilot`. Frontend code is in `frontend/src`, with `App.vue`, API helpers in `src/api`, and reusable Vue components in `src/components`. Project documentation and requirements are kept in `docs/`.

## Build, Test, and Development Commands

Run backend commands from `backend/`:

```bash
mvn spring-boot:run          # start API on http://localhost:8080
mvn test                     # run all backend tests
mvn test -Dtest=ClassName    # run one test class
mvn package -DskipTests      # build the Spring Boot jar
```

Run frontend commands from `frontend/`:

```bash
npm install      # install dependencies
npm run dev      # start Vite on http://localhost:5173
npm run build    # build production assets into frontend/dist/
npm run preview  # preview the production build
```

## Coding Style & Naming Conventions

Use Java 17 and Spring Boot conventions in the backend. Keep classes in the package matching their role, name tests as `*Test`, and prefer constructor injection or existing Spring patterns over ad hoc wiring. Risk rules belong in `service/risk/rules` and should implement `RiskRule`; component discovery handles registration. In the frontend, use Vue single-file components with PascalCase filenames for components and keep API calls in `src/api`.

## Testing Guidelines

Backend tests use JUnit through `spring-boot-starter-test`, with Mockito support and MockWebServer where external HTTP behavior must be simulated. Add focused unit tests beside the package being changed, and add integration or pipeline tests when behavior crosses fetch, parse, risk detection, agent, or reflection boundaries. Before submitting backend changes, run `mvn test`. Before submitting frontend changes, run `npm run build`.

## Commit & Pull Request Guidelines

Recent history uses short, descriptive subjects, sometimes with prefixes such as `feat:` or `chore:`. Keep commits scoped and imperative, for example `feat: add retry handling for review agent`. Pull requests should explain the behavior change, list verification commands, link related issues or PRs, and include screenshots or screen recordings for frontend UI changes.

## Security & Configuration Tips

Do not commit secrets. Configure `GITHUB_TOKEN` and `DEEPSEEK_API_KEY` through environment variables; `application.yml` reads placeholders for these values. Frontend code must not access API keys directly. Keep local overrides such as `.env` or `application-local.yml` out of git.
