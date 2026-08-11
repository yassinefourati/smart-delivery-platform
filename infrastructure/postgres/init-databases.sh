#!/bin/bash
# Creates one database per microservice inside the single shared Postgres instance
# used for local development. Each service only ever connects to its own database
# and is configured with credentials scoped to that database -- see docs/database-design.md.
#
# The official postgres image only runs docker-entrypoint-initdb.d scripts once,
# against the default POSTGRES_DB, so additional databases must be created here.
set -e

DATABASES="user_db product_db inventory_db order_db payment_db delivery_db notification_db"

for db in $DATABASES; do
  echo "Creating database '$db' if it does not already exist"
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" <<-EOSQL
    SELECT 'CREATE DATABASE $db' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = '$db')\gexec
EOSQL
done
