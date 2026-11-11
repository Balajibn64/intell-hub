# Tech Intelligence Hub (intell-hub)

A platform that ingests tech news, engineering content and industry signals, then summarizes, tags and ranks them per user. It is a real project and also a learning project for Claude Code. Explain the "why" briefly when introducing a new tool or pattern.

## Status
Planning phase. Track decisions and learning in `PROGRESS.md`. Do not write application code until the PRD and architecture are agreed, unless asked.

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

## Commands (fill in as the project is created)
- Backend: `mvn test`, `mvn spring-boot:run`
- Frontend: `npm run dev`, `npm test`, `npm run lint`
- Local stack: `docker compose up`
