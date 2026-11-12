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

Everything fetched is treated as hostile: only public addresses are ever contacted (checked when the
connection is made and again on every redirect), responses are size- and time-limited, HTML is reduced
to a small safe subset, and nothing from a feed is written to the logs.

