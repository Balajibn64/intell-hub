# Testing and Deployment Strategy

_Status: draft for review, 2026-11-11. Inputs: [Architecture](ARCHITECTURE.md), [Roadmap](ROADMAP.md)._

## 1. Testing

### Principles
- Tests are written with the behaviour, not afterwards (CLAUDE.md).
- Real providers and real feeds are never called in tests. Use a fake `SummarizerProvider` and a local fake feed server.
- Prefer a few realistic integration tests over many mock-heavy unit tests, and unit-test pure logic (ranking, parsing, budget, validation) thoroughly.

### Backend
| Layer | Tooling | What it covers |
|---|---|---|
| Unit | JUnit 5, AssertJ | Ranking formula, weight updates, dedupe and canonical URL logic, JSON response validation, budget math |
| Module | Modulith `@ApplicationModuleTest` | One module with its real wiring; events published and consumed |
| Integration | Spring Boot test, Testcontainers PostgreSQL | Repositories, Flyway migrations on a real database, the atomic budget update under concurrency |
| Web / security | MockMvc or WebTestClient | Status codes, 401 vs 403, validation errors, refresh rotation and replay revocation |
| Architecture | `ApplicationModules.verify()` | Module boundaries, no cycles; runs on every build |
| Fixtures | WireMock or a small local server | Messy RSS (bad encodings, broken XML, huge payloads, slow responses), provider 429s and malformed JSON |

Specific cases that must exist:
- Ingest is idempotent: running it twice stores no duplicates.
- One failing source does not stop the others.
- Oversized or slow responses are cut off.
- HTML is sanitized (script tags, event handlers, javascript: links).
- A prompt-injection article cannot change output shape or trigger actions.
- With the LLM down or the budget spent, the feed still works and recovers later.
- A USER gets 403 on every admin endpoint.
- Flyway migrations apply cleanly to an empty database.

### Frontend
Vitest and React Testing Library for components and flows (login, feed filters, save, interests). Mock the API layer at the network boundary. A small number of Playwright end-to-end checks may be added at M12.

### Coverage
No percentage target. Judge by the cases above and by reviewing each diff (`/code-review`, `/security-review` at M1 and M12).

## 2. CI (GitHub Actions)
**Decided (2026-11-11): add CI at the end, at M12 (hardening), not at M0.** Until then, `mvn test` and the frontend checks run locally before each commit, and Modulith `verify()` is part of `mvn test`. The trade-off is that early commits are not checked automatically and a broken build can go unnoticed on a branch, so run the full test suite before every push.

Planned setup for M12:
- On every pull request and push to a branch: `mvn test` (Testcontainers works on the standard runner), and later `npm run lint` and `npm test`.
- `main` is protected once the repo has a remote: changes arrive by pull request with checks green.
- Secrets used by CI are GitHub secrets. No real LLM keys are needed, since tests use fakes.
- Dependency updates are reviewed manually at first; Dependabot can be added later (new tooling, so discuss it first).

## 3. Environments
| Environment | Purpose | How |
|---|---|---|
| Local dev | Day-to-day work | `docker compose up` for Postgres, app run through `mvn spring-boot:run`, frontend through `npm run dev` |
| Local "prod-like" | End-to-end check | Full Compose stack with built images |
| Hosted | Decided after the build | Same Compose file or images; verify free-tier terms then |

## 4. Packaging
- Backend: multi-stage Dockerfile (Maven build, slim Java 21 runtime), run as a non-root user.
- Frontend: Vite build served as static files (for example by a small nginx container) that proxies `/api` to the backend, so the browser sees one origin and cookies and CORS stay simple.
- Compose services: `postgres` (with a named volume), `backend`, `frontend`.
- Health checks on every service; the backend waits for Postgres.

## 5. Configuration and secrets
Everything environment-specific is an environment variable, documented in `.env.example` (committed) with the real `.env` ignored by git.

| Variable | Purpose |
|---|---|
| DB URL, user, password | Database connection |
| JWT secret | Signing access tokens |
| Ingest secret | Protects `/internal/ingest` |
| Gemini API key, Groq API key | LLM providers |
| GitHub token | GitHub releases source |
| Admin email and initial password | First admin, created on first start |
| LLM daily budget (default 250) | Hard cap on calls |

The app fails fast on startup if a required secret is missing. Secrets are never logged.

## 6. Database operations
- Flyway runs on startup. Migrations are forward-only and never edited once applied.
- Backups: `pg_dump` on a schedule to a local folder, with a documented restore procedure tested once at M12. Retention is only 1-2 weeks of articles, so the data that really matters is users, sources and saved articles.

## 7. Scheduling
- Locally: `@Scheduled` triggers ingestion every few hours and the nightly purge.
- If hosted somewhere that sleeps: an external cron (for example GitHub Actions) calls `POST /internal/ingest`. The endpoint is idempotent, so a repeated or overlapping call is safe; a simple lock prevents two concurrent runs.

## 8. Observability
- Actuator health endpoint, structured logs with a request or run id.
- Counters: LLM calls by provider, budget remaining, enrichment states, per-source failures and last success.
- Admin status page (`/api/v1/admin/status`) is the main view; no Prometheus or Grafana in the MVP.

## 9. Release and rollback
- Tag releases; build images from the tag.
- Rollback means redeploying the previous image. Because migrations are forward-only, a migration that cannot be undone must be written in two steps (add, then later remove) so the old image keeps working.

## 10. Open items
1. ~~CI timing~~ Decided: at M12.
2. Whether to publish images to a registry (GitHub Container Registry is free, but defer until hosting is decided).
3. Backup destination once hosting is chosen.
