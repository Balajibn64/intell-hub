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
