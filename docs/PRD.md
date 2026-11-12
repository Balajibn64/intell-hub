# Tech Intelligence Hub: Product Requirements (MVP)

_Status: draft for review, 2026-11-11_

## 1. Problem
Keeping up with AI, software engineering and industry news means checking dozens of feeds, most of it noise. We want one place that collects the content, summarizes it, tags it and ranks it for each person.

## 2. Users
2-5 people (me and a small team). Each has an account, their own interest tags and their own ranking. This is not a public-scale product.

| Role | Can do |
|---|---|
| USER | Read the feed, save and click articles, manage own interests |
| ADMIN | Everything above, plus manage sources and trigger ingestion |

## 3. Goals and non-goals
**Goals (MVP)**
- Ingest from RSS/blogs, Hacker News, GitHub releases and arXiv/Hugging Face.
- Summarize, auto-tag and score every article.
- Rank the feed per user, using interest tags and weights learned from clicks and saves.
- Show it all in a web dashboard.

**Non-goals (MVP)**
- Email, chat or push delivery, and analytics.
- Daily synthesis digest.
- Layoffs and jobs, and company ranking (increment 2).
- Public sign-up, multi-tenant scale, mobile app.

## 4. Scope and volume
- ~30-50 feeds, ~200-500 articles/day, 1-2 weeks retained (nightly purge).
- Ingestion runs every few hours via `POST /internal/ingest`.

## 5. Functional requirements

### FR1 Accounts and auth
- Email/password login, JWT, roles USER and ADMIN.
- Accounts are created by an admin (no open registration).

### FR2 Sources
- Admin can add, disable and list sources. Types: RSS, HN, GitHub releases, arXiv/HF.
- Each source has a URL/config, a type and a last-fetched status.

### FR3 Ingestion
- `POST /internal/ingest` is protected by a shared secret, not a user JWT.
- Fetch with bounded timeouts and sizes; sanitize HTML; treat all content as untrusted.
- Deduplicate by canonical URL (and content hash as fallback).
- One failing source must not stop the others.

### FR4 AI enrichment
- Per article: summary, tags from a fixed taxonomy plus free tags, relevance score.
- All calls go through `SummarizerProvider`; no provider is called from business code.
- Provider chain: Gemini Flash-Lite primary, Groq fallback. Configured by env var; exact model IDs are set after checking live console limits.
- Hard daily budget of 250 LLM calls/day across all providers, enforced by a persisted counter (resets daily). Expected normal use is ~50-100; the rest is headroom for retries, lazy on-open enrichment and fallback. When the budget is spent, articles stay "not yet summarized" until the next day. Budget split: ~200 for batch enrichment, ~50 reserved for on-open requests.
- Minimize requests (not one per article):
  - Cheap pre-filter without the LLM: dedupe, source rules, keyword/tag heuristic score. Low scorers stay "excerpt only".
  - Batch 5-10 articles per call (title + truncated excerpt), one call returning summary, tags and score as JSON.
  - Lazy enrichment: unenriched articles are summarized on first open, then cached.
  - Never re-enrich an article already enriched (keyed by content hash).
- Rate-limited, retried with backoff, prioritized (newest and highest-signal first).
- Graceful degradation: when the LLM is unavailable or over limit, articles still appear with the title and excerpt, marked "not yet summarized", and are enriched later.

### FR5 Personalization
- Each user sets interest tags.
- Click and save events adjust per-user tag weights.
- Feed order = article relevance combined with the user's tag weights and recency.

### FR6 Dashboard
- Ranked feed with filters (category, tag, source, saved) and article detail.
- Save/unsave and mark-as-clicked.
- Shows summarization status and source health to admins.

### FR7 Retention
- Nightly job deletes articles older than the retention window, except saved ones (to confirm).

## 6. Non-functional requirements
- **Portability:** runs via Docker Compose locally; no host-specific code.
- **Security:** secrets in env vars only; password hashing; sanitized content; input validation.
- **Reliability:** idempotent ingestion; re-running never duplicates articles.
- **Cost:** free-tier LLM and hosting only. No paid dependency without discussion.
- **Testability:** Testcontainers for the backend; Modulith tests enforce module boundaries.

## 7. Proposed modules (input to architecture)
`identity`, `sources`, `ingestion`, `articles`, `enrichment` (LLM), `personalization`, `feed`, `retention`. Modules interact through published APIs or events only.

## 8. Success criteria
- A new article from a configured source appears in the feed within one ingestion cycle, with summary and tags.
- Two users with different interests see different orderings of the same articles.
- With the LLM disabled, the feed still works and recovers when it returns.
- The system runs end to end from `docker compose up`.

## 9. Open questions
1. Exact Gemini Flash-Lite and Groq model IDs: confirm against live console limits. (Provider order is decided.)
2. ~~Do saved articles survive the retention purge?~~ Yes, for as long as any user has them saved (decided in M9).
3. Company review data source for increment 2 (no free official API).
4. Reddit: the original list included it but MVP sources do not; confirm it stays out.

## 10. Roadmap
- **MVP:** everything above.
- **Increment 2:** layoffs and jobs sources, company ranking (reviews + funding).
- **Later:** daily synthesis, email/chat delivery, analytics, hosting on a free tier.
