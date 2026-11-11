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

## Test
```bash
cd backend && ./mvnw test      # needs Docker (Testcontainers starts Postgres)
```
