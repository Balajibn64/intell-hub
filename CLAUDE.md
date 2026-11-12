# Tech Intelligence Hub (intell-hub)

A platform that ingests tech news, engineering content and industry signals, then summarizes, tags and ranks them per user. It is a real project and also a learning project for Claude Code. Explain the "why" briefly when introducing a new tool or pattern.

## Status
Planning documents are in `docs/` (drafts, in review) and the build follows `docs/ROADMAP.md`, one milestone at a time. M0 (skeleton), M1 (auth), M2 (sources), M3 (RSS ingestion), M4 (feed API), M5 (enrichment with a fake provider) and M6 (Gemini and Groq providers) are built; do not build ahead of the current milestone unless asked. Track decisions and learning in `PROGRESS.md`.

## Stack (decided)
- Backend: Java 21, Spring Boot 4.x, Spring Modulith (modular monolith), Maven
- Database: PostgreSQL, Flyway migrations. Local via Docker Compose; Neon is a candidate for later.
- Frontend: React + JavaScript (no TypeScript), Vite, ESLint + Prettier. Use JSDoc `@typedef` for shared data shapes.
- LLM: a free hosted model behind a `SummarizerProvider` interface. Never call a provider directly from business code.
- Packaging: Docker. Hosting is undecided, so keep the app portable.

## Scope (MVP)
- Users: 2-5 people, email/password with roles (Spring Security + JWT)
- Sources: RSS/blogs, Hacker News, GitHub releases, arXiv/Hugging Face. Layoffs/jobs are increment 2.
- AI: per-article summary, auto-tagging, relevance scoring
- Personalization: per-user interest tags, plus weights learned from clicks and saves
- Delivery: web dashboard only
- Retention: 1-2 weeks of articles (a nightly purge job)
- Ingestion: scheduled batch, triggered through `POST /internal/ingest`

## Conventions
- Modules are independent: no cross-module imports of internal packages. Modules talk through published APIs or events, and Modulith tests enforce this.
- Schema changes go in Flyway migrations only. Never edit an applied migration and never use `ddl-auto` to change the schema.
- Free LLM limits change. Summarization must be rate-limited, retried and prioritized, and must degrade gracefully.
- Treat all fetched feed content as untrusted: sanitize HTML and bound sizes and timeouts.
- Keep secrets (API keys, JWT secret) in environment variables, never in the repo.
- Write tests with new behavior: JUnit 5 and Testcontainers for the backend, Vitest and React Testing Library for the frontend.
- Prefer small, reviewable commits. Do not commit to `main` directly once the repo has a remote.

## Do not
- Add dependencies, services or infrastructure beyond the MVP without discussing the trade-off first.
- Scrape sites that forbid it. Prefer official APIs and RSS.
- Introduce microservices, message brokers or Kubernetes.

## Git workflow
- GitHub flow: `main` only changes through pull requests. Branches are `<type>/<short-kebab-description>` (`feat/`, `fix/`, `docs/`, `chore/`, `refactor/`, `test/`).
- Commits follow Conventional Commits: `type(scope): imperative summary`, one logical change each. No `Co-Authored-By` or other attribution trailers.
- Run the full backend tests before every push (no CI until the hardening milestone).

## Commands
- Local database: copy `.env.example` to `.env`, set `DB_PASSWORD`, then `docker compose up -d postgres`
- Backend (from `backend/`; the app reads the repo-root `.env` itself): `./mvnw test`, `./mvnw spring-boot:run`
- Frontend (not created yet): `npm run dev`, `npm test`, `npm run lint`
- API prefix is `/api/v1`. Public: `/actuator/health` and `POST /api/v1/auth/{login,refresh,logout}`. Everything else needs a bearer token; `/api/v1/admin/**` needs the ADMIN role.
- Required env vars: `DB_PASSWORD`, `JWT_SECRET` (32+ chars). `ADMIN_EMAIL` and `ADMIN_PASSWORD` create the first admin when no users exist.
- The UI must render `contentSanitized` as HTML and everything else (titles, authors, excerpts, summaries, source names) as text.
- `ENRICHMENT_PROVIDER` is the ordered provider list (`gemini,groq`, `gemini`, `groq`, or `fake` for the offline stand-in, never mixed); unset means off (articles stay pending). Real providers need `GEMINI_API_KEY` / `GROQ_API_KEY` (startup fails without them); models via `GEMINI_MODEL` (gemini-3.5-flash-lite) and `GROQ_MODEL` (openai/gpt-oss-20b). Daily budget `ENRICHMENT_DAILY_BUDGET` (250) with `ENRICHMENT_RESERVED_CALLS` (50) held back for readers' requests. Model output is untrusted: it goes through `EnrichmentValidator` and is shown as text.
- `INGEST_SECRET` (32+ chars) enables `POST /internal/ingest` (header `X-Ingest-Token`); unset means it refuses every call. Tests never use the real network: they use a local fake server and fake DNS.
- After deleting or renaming a class, run `./mvnw clean verify`; stale `.class` files in `target/` can break the context.
