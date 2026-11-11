# Technology Selection and Justification

_Status: draft for review, 2026-11-11. Inputs: [PRD](PRD.md), CLAUDE.md, PROGRESS.md._

Selection criteria, in order: fits 2-5 users and ~500 articles/day; free or near-free; portable; learnable and testable; no infrastructure beyond the MVP.

## 1. Backend: Java 21 + Spring Boot 4.x + Spring Modulith (decided)
| Option | Verdict |
|---|---|
| **Spring Boot + Modulith** | Chosen. Mature security, scheduling, JPA and testing. Modulith gives enforced module boundaries inside one deployable, which is what we want at this scale |
| Plain Spring Boot, layered | Boundaries erode over time; no test that enforces them |
| Microservices | Rejected (CLAUDE.md): operational cost with no benefit for 2-5 users |
| Node/NestJS, Python/FastAPI | Viable, but the owner chose Java, and Modulith's boundary tests have no equivalent we would get for free |

Notes: Boot 4.1.2 is what Initializr offers now. The Boot 3-era `@MockBean` is gone, so Boot 4 testing idioms apply. Check Modulith's Boot 4 compatibility when generating the skeleton (via context7).

## 2. Database: PostgreSQL + Flyway
| Option | Verdict |
|---|---|
| **PostgreSQL** | Chosen. Relational model fits users, articles, tags, events. Built-in full-text search covers MVP search needs |
| SQLite/H2 | Poor parity with production and Testcontainers; no concurrent-writer comfort for ingestion plus requests |
| MongoDB | No benefit; our data is relational |

- **Flyway** for all schema changes (no `ddl-auto`).
- **Local:** Docker Compose. **Later:** Neon free tier is a candidate; verify its current free-tier terms before relying on it.
- **Search:** Postgres full-text search first. No Elasticsearch or vector database in the MVP.

## 3. Frontend: React + JavaScript + Vite
| Option | Verdict |
|---|---|
| **React + Vite (JS)** | Chosen. Fast dev loop; JSDoc `@typedef` for shared shapes instead of TypeScript (owner decision) |
| Next.js | SSR is unnecessary for a login-gated dashboard |
| Server-rendered (Thymeleaf/HTMX) | Simpler, but the owner wants to learn React |

Tooling: ESLint + Prettier, Vitest + React Testing Library. UI design happens before scaffolding (decided in PROGRESS.md).

## 4. Auth: Spring Security + JWT
- Email/password, BCrypt hashing, roles USER/ADMIN, admin-created accounts.
- **Decided:** short-lived access JWT (~15 min) plus a rotating refresh token in an HttpOnly cookie. Refresh tokens are stored hashed in Postgres (own Flyway migration), replaced on every use, and revoked on logout. If a rotated token is replayed, revoke that user's token family.
- `/internal/ingest` uses a shared secret, separate from user auth.
- Alternatives (Keycloak, Auth0, Clerk) rejected: extra service or external dependency for 2-5 users.

## 5. LLM: behind `SummarizerProvider`
- **Primary:** Gemini Flash-Lite. **Fallback:** Groq. Chain order, retries, and a 250 calls/day budget are in the PRD (FR4).
- Limits come from third-party blogs and are unverified; confirm in each console. Gemini's free tier may use prompts for training, so never send user data.
- Integration: plain HTTP clients behind the interface rather than a heavy framework. Spring AI is a candidate to evaluate, but it is a new dependency, so discuss it before adding it.
- Alternatives considered: OpenRouter free models (50 requests/day, too tight), Mistral free tier (requires opting into training).

## 6. Ingestion libraries
| Need | Choice | Why |
|---|---|---|
| RSS/Atom | `com.rometools:rome` 2.1.0 behind our own `FeedParser` interface | Standard Java feed parser; reduced maintenance, so keep it swappable |
| HTTP | Spring `RestClient` or JDK `HttpClient` | No extra dependency; supports timeouts and size bounds |
| HTML sanitizing | OWASP Java HTML Sanitizer (or jsoup `Safelist`) | Feed content is untrusted |
| HN | Official Firebase/Algolia API | Official, no scraping |
| GitHub releases | GitHub REST API with token | Official; token in env var |
| arXiv/HF | arXiv API / Atom feeds; HF official endpoints | Official |

Reddit and anything that forbids scraping stay out.

## 7. Scheduling and background work
- `POST /internal/ingest` is the trigger, called by `@Scheduled` locally or a GitHub Actions cron when hosted.
- Nightly purge via `@Scheduled`.
- Enrichment runs as an in-process async worker over a database-backed queue (a status column on articles). No message broker (CLAUDE.md). Modulith application events connect modules.

## 8. Testing
- Backend: JUnit 5, Testcontainers (Postgres), Modulith `ApplicationModules.verify()`, WireMock or similar for provider and feed fakes.
- Frontend: Vitest + React Testing Library.
- LLM tests use a fake `SummarizerProvider`; never call real providers in CI.

## 9. Packaging and hosting
- Docker image for the backend, static build for the frontend, Docker Compose for local.
- **Decided:** local only (Docker Compose) for now. Choose hosting after the project is built, verifying free-tier terms at that point. The app must stay portable until then (no host-specific code; a Compose file that also runs on a plain VPS).

## 10. Observability (MVP-light)
Spring Boot Actuator health, structured logs, and counters for LLM calls and ingestion failures. No Prometheus/Grafana stack in the MVP.

## 11. Deferred dependencies (need a trade-off discussion first)
Spring AI, a cache layer (Redis), a vector database, a broker, an email provider, jdtls-lsp.

## 12. Open items
1. Pick the exact Spring Modulith version against Boot 4.1.x at skeleton time. Web search (2026-11-11): Modulith 2.x targets Boot 4, and 2.1.1 is the latest stable I found; 2.2 is still a milestone. Confirm in the Spring Boot/Modulith compatibility docs (context7 is not authorized in this session).
2. Rome: checked. Use `com.rometools:rome` 2.1.0 (old `net.java.dev.rome` coordinates are relocated). It is in reduced maintenance with few maintainers, so wrap it behind our own `FeedParser` interface to keep it replaceable. Confirm Java 21 behaviour with a test.
3. Hosting: decided later (see section 9).
4. Verify the exact Gemini Flash-Lite and Groq model IDs and limits in their consoles.
