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
  - Fixed from the M3 code review: titles and authors are kept as text and no longer parsed as HTML (a title like "Using <form> validation" used to be cut to "Using"), and invisible and direction-override characters are stripped; entry-level failures are logged by exception class and a source whose entries all fail is reported FAILED; the app refuses to start with a secret or admin password that still contains "change-me" (this also closes the same gap for `JWT_SECRET` from M1), and `INGEST_SECRET` is commented out in `.env.example`; no wasted SELECT before each article insert (`Persistable`); the DNS lookup has its own deadline; finished results are kept when a run hits its time limit or is interrupted, and a cancelled source no longer writes its health afterwards; shared `Text`, `Hashes` and `Placeholders` helpers replace duplicated code.
  - Deferred on purpose: (a) the content hash covers title plus the first 2000 characters, so two different posts with an identical title and identical opening would count as one; accepted as effectively a duplicate. An edited post at an existing URL is never updated: article updates are a follow-up. (b) `POST /internal/ingest` runs synchronously (a real run of 6 feeds takes about 2 s); if runs grow past a proxy's timeout, switch to 202 plus a status endpoint. (c) The UI must show titles, authors and excerpts as text, never as HTML; only `content_sanitized` may be rendered as HTML.
  - Not done on purpose: conditional GET (ETag), enrichment and pre-filter scoring (M5), feed API (M4), purge (M9), HN/GitHub/arXiv adapters (M8; other source types are skipped with a clear status), per-feed politeness delays.

- [x] M4 Feed API (branch `feat/feed-api`, PR pending): V5 `saved_articles` and `article_events` (per user, removed with the user or article) plus a search index over title, excerpt and summary; `ArticleCatalog` read API (newest first with a stable tie break, offset paging with hard limits, source/category/id filters, web-style full-text search, every value a bound parameter); `ReadingState` API (idempotent save and unsave as single statements, events only on real change, repeat clicks within 10 s count once); `CurrentUser` for other modules, taken from the verified token only; `GET /api/v1/feed`, `GET /api/v1/articles/{id}`, `PUT`/`DELETE .../save`, `POST .../click`. 59 new tests (512 total); mutation-checked: saved restriction, per-user flag, size cap, idempotence, index expression. Checked live against the 53 real articles with two users.
  - Tests found: a search string with a NUL character or lone surrogate made PostgreSQL error (a 500 for the user), so control characters and lone surrogates are cleaned from `q`; an ordering test was flaky because Java and PostgreSQL order UUIDs differently (signed versus unsigned).
  - Fixed from the M4 code review: simultaneous clicks are now deduplicated atomically (a per-user, per-article advisory lock; the test fails 4 of 4 runs without it); foreign-key failures are translated by constraint name and any other integrity error is no longer disguised as a 404; wrong-type parameters answer with the same `VALIDATION_FAILED` code as every other bad parameter; the controller maps a catalog rejection to 400 instead of 500; stale module doc corrected.
  - Deferred, with the reason: (a) `saved=true` loads at most 2000 saved ids and binds them into the query, so a user with more than 2000 saves would not see the oldest ones in the saved feed; fine for 2-5 users, switch to a keyset on saved time if it ever matters. (b) `saved_articles.article_id` is `ON DELETE CASCADE`, so **the M9 purge query must explicitly exclude saved articles**, and deleting a source also removes everyone's saved rows for its articles (the admin UI should warn; disabling is the normal way to stop a source). V5 is applied, so changing the key needs a new migration.
  - Contract for the UI (M11): render only `contentSanitized` as HTML; titles, authors, excerpts, summaries and source names are text.
  - Not done on purpose: tags and the `tag` filter and `/tags` (M5), ranking and `sort=relevance` (M7), lazy enrichment on open (M5), per-request account re-check (a disabled user's token works up to 15 minutes), purge of events and saved articles (M9).

- [x] M5 Enrichment with a fake provider (branch `feat/enrichment`, PR pending): V6 tags and article claim columns, V7 `llm_usage`; `ArticleEnrichment` API in `articles` (atomic `SKIP LOCKED` claiming, every transition guarded by the state it expects, stale and failed recovery, request-to-enrich); `SummarizerProvider` interface and `ProviderChain` (one budget call per attempt, bugs contained); `EnrichmentValidator` as the untrusted-output boundary; deterministic offline `FakeSummarizer`; persisted daily budget (advisory lock, reserve for readers' requests, configurable day boundary); taxonomy of 34 curated tags and a free keyword pre-filter; background worker (recovery, triage, batching, exponential backoff, no overlap); feed additions (tags, `tag` filter, `/tags`, open-to-enrich). Off unless `ENRICHMENT_PROVIDER=fake`. 139 new tests (653 in total), including 6 scheduling tests that had not been running; each guard mutation-checked. Checked live on the 53 real articles: 33 enriched and 20 left excerpt-only in 7 calls, on-open queued and enriched within a poll, budget stopped work at the cap and the count survived a restart, nothing stuck `ENRICHING`, no titles, summaries or secrets in the log.
  - Found by tests: the budget's atomicity must not depend on a Spring proxy (now an explicit transaction); a rejected item was re-claimed in the same run and burned all its attempts at once (now held until the run ends); nested static test classes were never run by Maven, which also hid the M3 ingestion scheduling tests (now top-level and passing).
  - Fixed from the M5 code review: a provider outage or spent budget no longer uses up articles' retries (the claim's attempt is refunded); the budget is checked before anything is claimed, so a spent budget costs one query per poll instead of a claim and a release; readers' requests and background work are claimed as separate batches so background articles can never spend the reserve (a test records which part of the budget each batch is charged to; the first version of the tests could not detect a mislabel); the free-tag cap counts only tags that some article uses; a provider setting with a stray space fails at startup; queued readers' requests are capped at 50; the tag format is defined once; the reading side has its own narrow `EnrichmentRequests` port; the fake provider's tags are labelled HEURISTIC.
  - Deferred from the review: tag linking does several small queries per tag (negligible at about 11 batches a day, batch it if enrichment volume grows); there is no per-user limit on opening skipped articles (the global cap of 50 queued requests and the daily reserve bound the damage); **the M9 purge must delete free tags that no article uses any more** (they no longer count against the cap or appear in the tag list, but the rows remain).
  - Deferred: real providers, prompts, rate-limit handling and prompt-level injection defences (M6); per-user ranking (M7); admin retry of failed articles; a provider's own day boundary (the zone is configurable, to be set with the real limits).

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

- [x] M6 Real providers (branch `feat/real-providers`, PR pending): `GeminiSummarizer` (generateContent with JSON schema) and `GroqSummarizer` (chat completions, strict JSON schema, `openai/gpt-oss-20b`) behind the existing `SummarizerProvider` and `ProviderChain`; one shared `ProviderHttp` (JDK `HttpClient`, no new dependency); a shared `SummaryPrompt` with delimited article blocks and the answer schema; `ENRICHMENT_PROVIDER` is now an ordered list validated at startup, keys only from env, startup fails naming a missing key variable.
  - Docs check (2026-11-12): Gemini's newest docs show an `interactions` endpoint; we use the long-standing generateContent, and the live check confirms it. Gemini's daily quota resets at midnight Pacific, so the budget zone now defaults to `America/Los_Angeles`. Groq's free plan: 30 RPM, 1K requests/day, 8K tokens/min, 200K tokens/day on gpt-oss-20b; its daily reset time is not documented. Free-tier numbers differ between sources, so the provider's 429 is the real limit and the budget is configuration.
  - Tests use local stub servers only (no real network).
  - Live check with real keys (2026-11-12, budget 6): Gemini `gemini-3.5-flash-lite` via generateContent worked first time (6 calls, 30 articles, 0 failures, valid schema output, sensible summaries and tags); Groq `openai/gpt-oss-20b` alone worked (3 calls, 13 articles, 0 failures); with a bad Gemini key the first call failed, Groq answered and the batch was enriched without a retry (`llm_usage` showed gemini 1 call 1 failure, groq 1 call). No key or article text in the app log. Gemini needed no `interactions` endpoint.
  - Fixed from the M6 code review: an unusable answer (blocked, truncated, not JSON) now counts as an attempt, so a batch that breaks the provider ends FAILED after 3 tries instead of looping and draining the budget (a rejected key, 401 or 403, is the provider's problem and keeps attempts); the reply read is bounded by the total time limit and cut off if it stalls after the headers; Gemini gets 4000 output tokens and a cut-off answer is detected; a model index that does not fit an int is skipped instead of wrapping onto another article; the chain keeps the longest retry hint across providers; model names cannot be `.`/`..`; API keys are stripped and validated at startup; triple angle brackets in article text become look-alike quotes instead of being deleted.
  - Deferred on purpose: the budget day boundary is one setting for all providers (Pacific by default for Gemini; Groq's reset is undocumented), so mixed use may count a call in the neighbouring day; a revoked key costs at most about 2 calls per 15 minutes until fixed and shows as a warning in the log.
