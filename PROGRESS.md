# Tech Intelligence Hub: Progress Tracker

_Last updated: 2026-11-11_

## Requirement decisions (confirmed)
| Topic | Decision |
|---|---|
| Audience | Me + a small team (auth and shared/per-user preferences needed, but not public-scale) |
| Categories | AI/LLMs/ML · Software engineering · Industry & startups · Layoffs & job openings at good companies |
| Budget/hosting | Free-tier cloud (small LLM API budget TBD) |
| Delivery (MVP) | Web dashboard only. Email, chat and analytics come later. |

| AI features (MVP) | Per-article summary · Auto-tagging/categorization · Relevance scoring (daily synthesis deferred) |
| Sources | RSS/blogs/newsletters · HN + Reddit + GitHub Trending · Layoffs/jobs · arXiv, plus extra sources suggested by Claude |
| Auth | Email/password + roles (Spring Security + JWT) |
| Ingestion | Scheduled batch every few hours (triggered by `POST /internal/ingest`; GitHub Actions cron or `@Scheduled` depending on host) |
| Stack | Spring Boot 4.1.2 + Java 21 (Spring Modulith modular monolith) · React + JavaScript + Vite (ESLint + Prettier, JSDoc for shared shapes) · PostgreSQL (Neon later) + Flyway · Docker |
| Hosting | Local Docker Compose first; deploy later after verifying free-tier terms |
| LLM | Free hosted model behind a `SummarizerProvider` interface; provider chosen after checking live console limits (Gemini and Groq candidates; all figures unverified) |

## Open questions
- [x] Round 1: audience, categories, budget, delivery
- [x] Round 2: AI features, sources, auth, scheduling
- [x] Round 3: LLM = free model (hosted free tier, to be chosen in architecture phase) · Company ranking = auto-ranked by signals · Stack = compare alternatives first
- [x] Round 4: Retention = 1-2 weeks of articles · Personalization = per-user interest tags, plus learning from clicks/saves · Company ranking signals = reviews + funding · MVP sources = RSS + HN + GitHub releases + arXiv/HF; layoffs/jobs in increment 2
- [x] Scale: 2-5 users, ~30-50 feeds (~200-500 articles/day)
- [ ] Still open: review data source for company ratings (no free official API for Glassdoor-style reviews; to be researched before increment 2)

## Environment readiness
- [x] Git repo initialised (`intell-hub/`)
- [x] Docker 29.1
- [x] Maven 3.8.7
- [x] Java 21 (Temurin via SDKMAN), Maven 3.8.7 runs on it
- [x] Node v26.13.0 LTS + npm 11.20 (via nvm)
- [x] `gh` 2.45 installed
- [x] `gh` authenticated (account Balajibn64, origin set)

## Claude Code learning checklist
### Tier 1
- [ ] CLAUDE.md and project rules
- [x] Plan Mode (planned the skeleton, rejected and revised the first plan)
- [ ] Permissions / settings.json
- [ ] Git + `gh` workflow
- [ ] Memory, `/compact`, `/clear`
### Tier 2
- [ ] Subagents (Explore, Plan, custom reviewer)
- [ ] Hooks
- [ ] Predefined skills: code-review, simplify, security-review, claude-api, loop, schedule
- [~] MCP servers: context7 installed (restart/reload to activate). GitHub MCP skipped (gh CLI enough), Postgres/Playwright later
### Tier 3
- [~] Custom skills: `intellhub-backend-conventions` written (project scope). More later: /add-source, /new-migration, /adr, /progress
- [ ] Plugins
- [ ] Worktrees / parallel agents / Workflows
- [ ] Agent SDK

## Deliverables
- [~] PRD: draft in `docs/PRD.md`, awaiting review
- [~] Claude Code tools & skills checklist: draft in `docs/LEARNING.md`, awaiting review
- [~] Technology selection & justification: draft in `docs/TECH-SELECTION.md`, awaiting review
- [~] Architecture + Mermaid diagrams: draft in `docs/ARCHITECTURE.md`, awaiting review
- [~] Database & API design: draft in `docs/DATABASE-API.md`, awaiting review
- [x] MVP scope & roadmap: `docs/ROADMAP.md`, milestone order confirmed 2026-11-11 (GitHub Actions CI added at the end, in M12)
- [~] Learning roadmap: in `docs/LEARNING.md`, awaiting review
- [~] Testing & deployment strategy: draft in `docs/TESTING-DEPLOYMENT.md`, awaiting review

## Build progress (see docs/ROADMAP.md)
- [x] M0 Backend skeleton (branch `feat/backend-skeleton`, PR pending): Boot 4.1.2 + Modulith 2.1.1, eight module packages, Compose Postgres 17, Flyway V1 baseline, health-only security, Modulith and Testcontainers tests green. API prefix is `/api/v1`.
- [x] M1 Auth (branch `feat/auth`, PR pending): V2 identity schema, BCrypt, HS256 access JWT via Spring's OAuth2 resource server starter (the one new dependency), rotating refresh tokens (hashed, family revocation on replay, row lock for concurrent use), login throttling, `/me`, admin user endpoints, first-admin bootstrap, shared error model. 73 tests. Checked end to end with curl against Compose Postgres.
  - CSRF: framework stays off (stateless bearer API); the two cookie endpoints require `X-Requested-By: intellhub` and an allowed `Origin`, on top of `SameSite=Strict`. Generated-password user is gone.
  - Known limits: two tabs refreshing with one cookie at the same moment will log the user out (SPA must serialize refresh); the login limiter is in memory (resets on restart, single instance); an access token stays valid up to 15 min after an account is disabled, though `/me` re-checks the account. Deferred: `PATCH /admin/users/{id}`, `PUT /me/password`.
  - Deferred from the M1 code review (decide when each becomes real): (1) concurrent refresh with one cookie signs the user out; consider a short reuse grace window if the SPA cannot serialize refreshes. (2) The login limiter keys on `getRemoteAddr()`; behind a reverse proxy every client shares one address, so set trusted forwarded headers when hosting is chosen. (3) `refresh_tokens` rows are never purged: the M9 retention job must delete expired and revoked rows and add an `expires_at` index. (4) No CORS: the M11 frontend should use the Vite dev proxy and nginx for same-origin calls, or add CORS driven by `allowed-origins`. (5) Admin endpoints trust the role claim for up to 15 minutes: re-check the account per request when `PATCH /admin/users/{id}` is added. (6) Minor: Spring Data `save()` does an extra SELECT for app-assigned UUIDs; the limiter wipes all state when its key cap is hit.
  - Fixed from the review: login no longer holds a DB connection during BCrypt; the throttle records the attempt before the password check and refunds it on success, so a parallel burst cannot slip past; only the email unique constraint maps to `EMAIL_TAKEN`.
  - Lesson: after deleting a class, `./mvnw clean verify`; a stale `.class` caused a confusing duplicate-bean failure.

Notes from M0: Initializr's Modulith option adds JPA event-registry and observability artifacts; trimmed them (the registry needs a table, which must come through Flyway, so add it deliberately when events need persistence). `UserDetailsServiceAutoConfiguration` still logs a generated password in M0; it disappears when real auth lands in M1.

- [x] M2 Sources (branch `feat/sources`, PR pending): V3 `sources` schema, `SourceCatalog` published API for ingestion (enabled sources, report fetch results with atomic health counters), admin CRUD under `/api/v1/admin/sources`, strict URL validation, bounded JSON config, seeder for six verified public RSS feeds (only on an empty table; `SEED_DEFAULT_SOURCES`), and an `/api/v1/admin/**` route rule so any future admin controller is ADMIN-only. 132 new tests (211 total). Checked end to end with curl.
  - **M3 must:** resolve and check the address at fetch time and on every redirect (the M2 validator is text-only and never resolves DNS). Recorded in ARCHITECTURE.md and ROADMAP.md.
  - Per-type config rules (HN, GitHub releases, arXiv) arrive with each adapter; M2 only bounds the config object.
  - Not done on purpose: auto-disabling failing sources, DNS-based checks, UI.
  - Fixed from the M2 code review: concurrent edit vs fetch report (lost update), seeder skipping bad entries one by one, IPv6 embedded-IPv4 bypasses (NAT64, 6to4, IPv4-compatible), URL equivalents (trailing dot, default port), deep read-only config, error text cleaning. Left as is: deleting every source brings the starter feeds back on restart (set `SEED_DEFAULT_SOURCES=false`); the redundant `saveAndFlush` in `update` is harmless.

- [x] M3 RSS ingestion (branch `feat/ingestion`, PR pending): V4 `articles` (unique canonical URL and content hash), `ArticleStore` public API, shared `PublicUrlPolicy` and `PublicAddressPolicy`, SSRF-safe fetcher (Apache HttpClient 5 with a vetting DNS resolver, manual redirect re-checks, decoded-size cap with immediate abort, total deadline), Rome behind `FeedParser` with DOCTYPEs refused, jsoup sanitizer and plain-text extraction, URL canonicalisation and content hashing, the ingestion run with per-source isolation and health reporting, `POST /internal/ingest` on its own security chain (shared secret, constant-time compare, uniform 403, no JWT), optional in-app schedule (off by default). 204 new tests (434 total); each key guard was mutation-checked (DNS vetting, size cap, redirect re-validation, DOCTYPE refusal, sanitizer allowlist, unset-secret guard). Checked live: 53 articles from 6 real feeds in 2.2 s, second run creates 0, hostnames that resolve to loopback or the metadata address are blocked with "Blocked address", stored content has no disallowed markup, the app log has no feed text, URLs or secrets.
  - A concurrency test found a real bug: two feeds carrying one story ingested in parallel both passed the content-hash check. Fixed with a unique constraint on `content_hash`.
  - New env vars: `INGEST_SECRET` (required for the endpoint), `INGEST_SCHEDULE_ENABLED`, `INGEST_CRON`.
  - Not done on purpose: conditional GET (ETag), enrichment and pre-filter scoring (M5), feed API (M4), purge (M9), HN/GitHub/arXiv adapters (M8; other source types are skipped with a clear status), per-feed politeness delays.

## Skills and plugins decisions (2026-11-11)
| Item | Decision | Why |
|---|---|---|
| feature-dev, commit-commands, claude-md-management, context7 | Installed (project scope) | Local prompts only, except context7, which is a remote docs server (never send project details) |
| security-guidance | Deferred | Opus review of every diff on each turn/commit plus pip install at session start; use built-in `/security-review` for now |
| code-review plugin | Skipped | Duplicates built-in `code-review` skill |
| github MCP | Skipped | Needs a PAT env var; `gh` CLI already authenticated |
| jdtls-lsp | Deferred to backend skeleton | Needs manual jdtls download on Linux |
| kousen `spring-boot-skill` | Reviewed, not installed as-is | Layer packages, Lombok `@Data`, Boot 3 `@MockBean` conflict with Modulith/Boot 4. Good parts folded into our custom skill |
| LLM providers | Gemini Flash-Lite primary, Groq fallback, behind `SummarizerProvider` | Web research (Nov 2026, third-party sources, unverified): Gemini Flash-Lite ~500 req/day, full Flash ~20/day; Groq limited by tokens/day (~200-500K), so it cannot be sole provider. Verify in AI Studio and Groq consoles |
| Reducing LLM requests | Heuristic pre-filter, batch 5-10 articles per call, lazy enrichment on first open, no re-enrichment by content hash. Hard cap 250 calls/day (persisted counter; ~200 batch + ~50 reserved for on-open), expected use ~50-100 | Keeps us at about half of Gemini's reported free limit, with headroom |
| Refresh tokens | Short access JWT + rotating refresh token (HttpOnly cookie, hashed in Postgres) | Real logout/revocation, token not readable by JS |
| Hosting | Local Docker Compose only for now; decide after the build | Verify free-tier terms later; stay portable |
| Rome / Modulith checks | Rome 2.1.0 behind a `FeedParser` interface; Modulith 2.1.x targets Boot 4 (confirm exact version at skeleton time) | Web search, 2026-11-11 |
| Spring Boot version | 4.1.2 (Initializr no longer offers 3.x) | Update CLAUDE.md: still says Boot 3 |
| Frontend | Deferred: study `frontend-design` + design the UI first, then scaffold | User decision |
