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
