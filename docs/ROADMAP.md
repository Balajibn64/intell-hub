# MVP Scope and Roadmap

_Status: draft for review, 2026-11-11. Inputs: [PRD](PRD.md), [Architecture](ARCHITECTURE.md), [Database and API](DATABASE-API.md)._

## 1. Approach
Build in **vertical slices**: each milestone ends with something that runs and is tested, so there is always a working app. One small, reviewable commit per step, on feature branches (never directly on `main` once the repo has a remote). Each milestone is also a chance to learn one Claude Code tool, listed in section 4.

The LLM comes late on purpose. Ingestion and the feed work first with a fake `SummarizerProvider`, so the core is testable and free before any real provider is involved.

## 2. In scope (MVP)
Accounts and auth, source management, ingestion from RSS, HN, GitHub releases and arXiv/Hugging Face, AI enrichment (summary, tags, score) within a 250 calls/day budget, per-user interests and click/save learning, a ranked web dashboard, nightly retention purge.

## 3. Milestones

| # | Milestone | Done when | Main modules |
|---|---|---|---|
| M0 | Backend skeleton | App boots; Postgres via Compose; Flyway runs V1; Modulith `verify()` test passes; health endpoint; CI-less `mvn test` green | all (empty) |
| M1 | Auth | Admin from env vars; login, refresh rotation, logout; role checks; tests for 401 and 403 | identity |
| M2 | Sources | Admin CRUD for sources; validation; seed a few RSS sources | sources |
| M3 | Ingestion (RSS), done | `POST /internal/ingest` fetches RSS with timeouts and size caps, sanitizes HTML, dedupes, stores articles; one bad source does not stop the run; **fetch-time SSRF protection** (resolve and check the address, re-check on every redirect) | ingestion, articles |
| M4 | Feed API (unranked) | Paginated feed with filters and detail, newest first; save and click endpoints | articles, feed |
| M5 | Enrichment with fake provider | Pending articles are batched, enriched by a fake provider, states update, budget counter enforced; restart resumes | enrichment |
| M6 | Real providers | Gemini Flash-Lite and Groq behind `SummarizerProvider`; fallback chain; 429 backoff; strict JSON validation; prompt-injection tests | enrichment |
| M7 | Personalization and ranking | Interests API; click/save weights; ranked feed; two users see different orders | personalization, feed |
| M8 | More sources | HN, GitHub releases, arXiv/Hugging Face adapters | ingestion |
| M9 | Retention and ops | Nightly purge (saved articles kept); admin status endpoint; source health | retention, sources |
| M10 | UI design | Screens and flows designed (see PROGRESS.md: study `frontend-design` first) | none |
| M11 | Frontend | Vite app: login, feed with filters, article detail, interests, admin pages; Vitest + RTL tests | frontend |
| M12 | Hardening | Security review, full end-to-end run from `docker compose up`, README, backup and restore notes, GitHub Actions CI (backend and frontend tests, lint) | all |

M10 can run in parallel with M5-M9, because it needs no backend code. M11 depends on the API being stable from M4 onward.

## 4. Claude Code learning per milestone
| Milestone | Tool to practise |
|---|---|
| M0 | `feature-dev` plugin, Plan Mode for the skeleton, context7 (once authorized) to confirm Modulith and Boot 4 versions |
| M1 | `/security-review` on the auth code |
| M2-M3 | The `intellhub-backend-conventions` skill in use; a custom reviewer subagent |
| M4 | `/code-review` and `/simplify` on a real diff |
| M5-M6 | Hooks (for example, run tests or block edits to applied migrations) |
| M7 | Worktrees or parallel agents for independent work |
| M8 | A custom skill such as `/add-source` once the adapter pattern repeats |
| M9 | `/schedule` or `/loop` for recurring checks |
| M10-M11 | `frontend-design` skill, Playwright MCP |
| M12 | `/security-review`, `/claude-md-management:revise-claude-md` |

## 5. Definition of done (every milestone)
- Tests written with the behaviour: JUnit 5 and Testcontainers (backend), Vitest and React Testing Library (frontend).
- Modulith `verify()` passes.
- Schema changes only in new Flyway migrations.
- No secrets in the repo.
- `PROGRESS.md` updated; commit messages are small and clear.

## 6. Increment 2 (after the MVP)
Layoffs and job openings sources, then company ranking from reviews and funding. The review data source (no free official API) must be researched before this starts. Each new source should also be checked for terms of use, using official APIs and RSS only.

## 7. Later
Daily synthesis digest, email and chat delivery, analytics, hosting (decide after the build, verify free-tier terms then).

## 8. Risks to schedule
| Risk | When it shows up | Response |
|---|---|---|
| Free LLM limits differ from the research | M6 | Budget cap, chain, and degrade gracefully are already designed; adjust the batch size |
| Boot 4 and Modulith version friction | M0 | Pin versions, confirm the compatibility matrix first |
| Feeds are messy (encodings, broken XML, huge payloads) | M3 | Size caps, timeouts, per-source isolation, fixture-based tests |
| Scope creep | Any time | Anything not in section 2 needs a trade-off discussion first (CLAUDE.md) |

## 9. Open items
1. ~~Confirm milestone order~~ Confirmed by owner (2026-11-11).
2. ~~CI timing~~ Decided: GitHub Actions is added at the end, in M12. Run the full local test suite before every push until then. The branch and pull request workflow still applies from the start.
