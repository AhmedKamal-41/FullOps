#!/usr/bin/env bash
# Runs once, automatically, the first time the postgres container starts with an
# empty data directory (standard docker-entrypoint-initdb.d behavior). Creates one
# database and one owning user per service, then revokes the default PUBLIC connect
# privilege so a service's credentials cannot reach another service's database —
# see docs/ARCHITECTURE.md.
set -euo pipefail

create_service_database() {
  local db_name=$1
  local db_user=$2
  local db_password=$3

  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres <<-SQL
    CREATE USER "${db_user}" WITH PASSWORD '${db_password}';
    CREATE DATABASE "${db_name}" OWNER "${db_user}";
    REVOKE ALL ON DATABASE "${db_name}" FROM PUBLIC;
SQL
}

create_service_database "order_db" "order_service" "${ORDER_DB_PASSWORD}"
create_service_database "inventory_db" "inventory_service" "${INVENTORY_DB_PASSWORD}"
create_service_database "payment_db" "payment_service" "${PAYMENT_DB_PASSWORD}"
create_service_database "fulfillment_db" "fulfillment_service" "${FULFILLMENT_DB_PASSWORD}"
