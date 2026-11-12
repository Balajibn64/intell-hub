# Tech Intelligence Hub

Ingests tech news and engineering content, then summarizes, tags and ranks it per user.
Planning documents are in [docs/](docs/). Progress is tracked in [PROGRESS.md](PROGRESS.md).

## Run the backend locally
Requirements: Java 21, Docker.

```bash
cp .env.example .env          # then set DB_PASSWORD
docker compose up -d postgres
cd backend
./mvnw spring-boot:run               # reads ../.env; real environment variables win
curl localhost:8080/actuator/health   # {"status":"UP", ...}
```

`DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER` and `DB_PASSWORD` configure the connection (the same
names Compose uses). Set `DB_URL` to override the whole JDBC URL.

## Authentication
The app will not start without `JWT_SECRET` (at least 32 characters, for example
`openssl rand -hex 32`). On first start, if there are no users, it creates an administrator from
`ADMIN_EMAIL` and `ADMIN_PASSWORD` (12 to 72 characters). After that those two variables are
ignored. Accounts are created by an admin; there is no public sign-up.

```bash
curl -X POST localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"<ADMIN_EMAIL>","password":"<ADMIN_PASSWORD>"}'      # returns an access token (15 min)
curl localhost:8080/api/v1/me -H "Authorization: Bearer <accessToken>"
```

The login response also sets an HttpOnly refresh cookie (14 days, path `/api/v1/auth`). The
browser app calls `POST /api/v1/auth/refresh` and `/logout` with the header
`X-Requested-By: intellhub`; each refresh rotates the cookie, and reusing an old one signs that
login out everywhere.

## Test
```bash
cd backend && ./mvnw test      # needs Docker (Testcontainers starts Postgres)
```

## Sources
Admins manage content sources under `/api/v1/admin/sources` (list, create, update, delete). On a
fresh database six public RSS feeds are added automatically; set `SEED_DEFAULT_SOURCES=false` to
skip that. A source's type and URL cannot be changed (add a new source instead), and disabling is
the normal way to stop one. URLs must be public http(s) addresses: credentials, `localhost`, private
and link-local addresses and numeric IP tricks are rejected.

```bash
curl localhost:8080/api/v1/admin/sources -H "Authorization: Bearer <admin accessToken>"
```

## Reading the feed
All of these need a bearer token (any role). `GET /api/v1/feed` lists articles ranked for the signed-in
reader (`sort=relevance`, the default) or newest first (`sort=newest`); also `page`, `size` up to 50, `q` for
full-text search, `source`, `category`, `tag`, `saved=true`. Ranking combines the article's relevance, how well
its tags match what you care about, and recency. You tell it what you care about with
`PUT /api/v1/me/interests` (`{"interests":[{"tag":"llm","weight":1.0}]}`, at most 50, weight 0.1 to 1) and read it
back with `GET /api/v1/me/interests`; your clicks and saves also teach it (a click adds a little, a save more;
what is learned fades by half every two weeks). Everything personal is yours alone. And
`GET /api/v1/articles/{id}` returns one article with its sanitized content. `PUT` and `DELETE`
`/api/v1/articles/{id}/save` and `POST /api/v1/articles/{id}/click` change only the caller's own state.
Show `contentSanitized` as HTML and everything else as text.

```bash
curl "localhost:8080/api/v1/feed?q=kubernetes&size=10" -H "Authorization: Bearer <accessToken>"
```

## Enrichment
Enrichment adds a summary, tags, a category and a relevance score to articles, a few at a time, in the
background. It is **off unless you set `ENRICHMENT_PROVIDER`**. The value is the providers to try, in order,
with no spaces: `gemini,groq` uses Gemini Flash-Lite first and falls back to Groq (`openai/gpt-oss-20b`)
when Gemini fails or is rate limited. Each listed provider needs its key in the environment or the
gitignored `.env` (`GEMINI_API_KEY`, `GROQ_API_KEY`); the app refuses to start without it. `GEMINI_MODEL`
and `GROQ_MODEL` override the models. `fake` is an offline, deterministic stand-in (extractive summaries,
keyword tags) for trying the pipeline without keys; its results are labelled `fake` and it cannot be mixed
with real providers.

With a provider set, new articles go through a free keyword pre-filter. Clearly irrelevant ones only show
their excerpt (`EXCERPT_ONLY`) until someone opens them, which queues them for enrichment. The rest are
sent in batches of five. A persisted budget (`ENRICHMENT_DAILY_BUDGET`, default 250 calls a day, of which
`ENRICHMENT_RESERVED_CALLS`, default 50, are only for openings) keeps it inside free-tier limits; when it
is spent the remaining articles wait until tomorrow. The budget day starts at midnight Pacific time by
default (`ENRICHMENT_BUDGET_ZONE`), which is when Gemini's daily quota resets. Whatever a model returns is treated as untrusted and
validated; summaries, tags and names are text, never HTML.

## Ingestion
Ingestion fetches every enabled RSS source, cleans the entries and stores the new ones. It is started
with a shared secret (set `INGEST_SECRET`, 32+ characters; without it the endpoint refuses every call):

```bash
curl -X POST localhost:8080/internal/ingest -H "X-Ingest-Token: <INGEST_SECRET>"
```

The response is a summary of what happened per source. Each source's health (last status, last error
and consecutive failures) is visible to admins in `GET /api/v1/admin/sources`. To run it on a timer
inside the app set `INGEST_SCHEDULE_ENABLED=true` (default every 4 hours, `INGEST_CRON` to change); an
external cron calling the endpoint works too.

Titles, authors and excerpts are stored as plain text and must be shown as text, never as HTML; only `content_sanitized` is safe to render as HTML.

Everything fetched is treated as hostile: only public addresses are ever contacted (checked when the
connection is made and again on every redirect), responses are size- and time-limited, HTML is reduced
to a small safe subset, and nothing from a feed is written to the logs.

