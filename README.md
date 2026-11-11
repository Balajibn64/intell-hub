# Tech Intelligence Hub

Ingests tech news and engineering content, then summarizes, tags and ranks it per user.
Planning documents are in [docs/](docs/). Progress is tracked in [PROGRESS.md](PROGRESS.md).

## Run the backend locally
Requirements: Java 21, Docker.

```bash
cp .env.example .env          # then set DB_PASSWORD
docker compose up -d postgres
set -a && . ./.env && set +a
cd backend
./mvnw spring-boot:run
curl localhost:8080/actuator/health   # {"status":"UP", ...}
```

## Test
```bash
cd backend && ./mvnw test      # needs Docker (Testcontainers starts Postgres)
```
