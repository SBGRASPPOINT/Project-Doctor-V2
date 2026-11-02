#!/usr/bin/env bash
# Produce the baseline schema (as applied by Hibernate from the @Entity classes) so it can be
# committed as a migration instead of being re-derived on every boot.
#
#   1. start a THROWAWAY database and boot the app against it
#        docker run --rm -d --name schema-dump -e POSTGRES_PASSWORD=postgres \
#            -e POSTGRES_DB=appointment -p 55432:5432 postgres:17-alpine
#        DB_URL=jdbc:postgresql://localhost:55432/appointment ./gradlew bootRun &
#   2. wait for /api/health, then run this script:
#        DB_URL=jdbc:postgresql://localhost:55432/appointment ./scripts/dump-schema.sh
#   3. review src/main/resources/db/migration/V1__baseline.sql, add the Flyway dependency,
#      set DDL_AUTO=validate and delete the throwaway database.
set -euo pipefail
cd "$(dirname "$0")/.."

DB_URL="${DB_URL:-jdbc:postgresql://localhost:55432/appointment}"
# jdbc:postgresql://host:port/db  ->  -h host -p port -d db
rest="${DB_URL#jdbc:postgresql://}"
hostport="${rest%%/*}"
db="${rest#*/}"
host="${hostport%%:*}"
port="${hostport##*:}"

echo "Dumping schema of ${db} at ${host}:${port} …"
PGPASSWORD="${DB_PASSWORD:-postgres}" pg_dump \
  -h "$host" -p "$port" -U "${DB_USER:-postgres}" \
  --schema-only --no-owner --no-privileges --if-exists "$db" \
  > src/main/resources/db/migration/V1__baseline.sql
echo "Wrote src/main/resources/db/migration/V1__baseline.sql ($(wc -l < src/main/resources/db/migration/V1__baseline.sql) lines)"
echo "Remember: drop the CREATE SCHEMA/search_path noise and keep only the tables, indexes and constraints."
