---
name: intellhub-backend-conventions
description: Conventions for writing or reviewing backend code in intell-hub (Spring Boot 4, Java 21, Spring Modulith, Postgres, Flyway). Use when creating or changing controllers, services, JPA entities, migrations, tests, or the LLM/ingestion code under backend/.
---

# intell-hub backend conventions

Stack: Java 21, Spring Boot 4.x, Spring Modulith, PostgreSQL, Flyway, Maven wrapper (`./mvnw`).
Boot 4 and Modulith 2 are newer than most tutorials. **Check version-specific APIs against current docs (context7 MCP) before using them**, and don't trust Boot 3 examples blindly.

## Structure: package by module, not by layer
- Top-level packages are modules: `com.intellhub.ingestion`, `content`, `personalization`, `users`.
- Inside a module, layering is optional and small. Do **not** create global `controller/`, `service/`, `repository/` packages.
- Only the module's root package is its public API. Internals go in sub-packages (`internal`) and are never imported by other modules.
- Cross-module calls use the other module's published API (a service interface or DTO) or application events. `ModularityTests` (`ApplicationModules.verify()`) must stay green.

## Code style
- Constructor injection only. Fields are `private final`. No `@Autowired` on fields. No Lombok.
- Prefer Java records for DTOs and events. Use `Optional` for absent results, not `null`.
- Keep controllers thin: validate (`@Valid`), delegate, map to a response. Business rules live in services.
- Return explicit status codes (`ResponseEntity`) where it matters. Version the API under `/api/v1/...`.
- One global `@RestControllerAdvice` maps domain exceptions to an error body. Don't leak stack traces.
- Comments explain non-obvious *why* only. Public module APIs get a short Javadoc.

## Persistence
- **Schema changes only through Flyway** (`db/migration/V<n>__description.sql`). Never edit an applied migration, add a new one. `spring.jpa.hibernate.ddl-auto` stays `validate`.
- Entities: no `@Data`. Define `equals`/`hashCode` deliberately (id-based). Keep relationships lazy. Avoid bidirectional mappings unless needed.
- Use `@Transactional` on service methods that write. Reads that return lazy data must finish inside the transaction.

## Ingestion and LLM rules (project-specific)
- Feed and article content is **untrusted**: sanitize HTML, cap sizes, set connect/read timeouts, never follow redirects blindly.
- Never call an LLM provider from business code. Go through the `SummarizerProvider` interface, with a rate limiter, retry with backoff and a prioritized queue. Free-tier limits change.
- Ingestion is triggered by `POST /internal/ingest` (protected) so GitHub Actions or `@Scheduled` can drive it.
- Secrets (API keys, JWT secret, DB password) come from environment variables, never committed.

## Testing
- Pick the lightest test that proves the behavior: unit test (plain JUnit + Mockito) for logic, `@WebMvcTest` for controller contracts, `@DataJpaTest` or full `@SpringBootTest` with a **Testcontainers PostgreSQL** for persistence and migrations.
- For mocking Spring beans in slice tests, use `@MockitoBean` (verify the exact Boot 4 package via context7). Do not use the Boot 3 `@MockBean`.
- Every new migration is exercised by at least one integration test that starts a real Postgres.

## Before declaring done
Run `./mvnw verify` from `backend/`. Report failures with output, don't summarize them away.

---
_Adapted in part from the MIT-licensed `kousen/claude-code-training` Spring Boot skill (constructor injection, validation, global exception handling, test layering), with changes for Modulith, Boot 4 and this project's rules._
