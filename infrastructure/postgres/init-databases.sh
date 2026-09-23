#!/bin/bash
# Creates one database per microservice inside the single shared Postgres instance
# used for local development. Each service only ever connects to its own database
# and is configured with credentials scoped to that database -- see docs/database-design.md.
#
# The official postgres image only runs docker-entrypoint-initdb.d scripts once,
# against the default POSTGRES_DB, so additional databases must be created here.
#
# --dbname is required, not decoration. The entrypoint exports PGUSER and PGPASSWORD
# but not PGDATABASE, so a bare psql connects to a database named after the USER
# ("sdp"), which does not exist because docker-compose.yml sets POSTGRES_DB=postgres.
# Without it this script failed on its first psql, set -e aborted the entrypoint, the
# container restarted onto an already-initialised data directory, skipped init, and
# came up healthy with none of these databases -- every service then died on
# 'database "user_db" does not exist'.
set -e

DATABASES="user_db product_db inventory_db order_db payment_db delivery_db notification_db"

for db in $DATABASES; do
  echo "Creating database '$db' if it does not already exist"
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "${POSTGRES_DB:-postgres}" <<-EOSQL
    SELECT 'CREATE DATABASE $db' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = '$db')\gexec
EOSQL
done
