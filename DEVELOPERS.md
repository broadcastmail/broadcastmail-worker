# Developing broadcastmail-worker

## Prerequisites

- Java 25
- Maven 3.9+
- Docker (for Testcontainers)
- broadcastmail-api running and DB migrated

## Environment variables

```bash
# PostgreSQL
PGHOST=
PGPORT=5432
PGDATABASE=railway
PGUSER=postgres
PGPASSWORD=

# Encryption — same key as API
ENCRYPTION_KEY=

# Unsubscribe — same secret as API
UNSUBSCRIBE_SECRET=

# GitHub Packages (for broadcastmail-common)
GITHUB_TOKEN=
```

## Running locally

```bash
./mvnw spring-boot:run
```

Worker starts polling for RESOLVING campaigns immediately. No HTTP server — background jobs only.

Note: Run broadcastmail-api first so Flyway migrations have run and the schema exists. Worker has Flyway disabled.

## Running tests

```bash
./mvnw verify -Dgroups='!local'
```

## Architecture

See [ARCHITECTURE.md](https://github.com/broadcastmail/.github/blob/main/ARCHITECTURE.md).

## Database schema

See [DATABASE.md](https://github.com/broadcastmail/.github/blob/main/DATABASE.md).
