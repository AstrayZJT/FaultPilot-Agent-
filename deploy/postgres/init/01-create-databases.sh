#!/usr/bin/env bash
set -euo pipefail

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  --set=faultpilot_app_password="$FAULTPILOT_APP_PASSWORD" \
  --set=lab_service_password="$LAB_SERVICE_PASSWORD" \
  --set=faultpilot_diagnostic_password="$FAULTPILOT_DIAGNOSTIC_PASSWORD" <<'SQL'
CREATE ROLE faultpilot_app LOGIN PASSWORD :'faultpilot_app_password';
CREATE ROLE lab_service LOGIN PASSWORD :'lab_service_password';
CREATE ROLE faultpilot_diagnostic LOGIN PASSWORD :'faultpilot_diagnostic_password';

CREATE DATABASE faultpilot OWNER faultpilot_app;
CREATE DATABASE faultpilot_lab OWNER lab_service;

\connect faultpilot_lab
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
GRANT CONNECT ON DATABASE faultpilot_lab TO faultpilot_diagnostic;
GRANT USAGE ON SCHEMA public TO faultpilot_diagnostic;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO faultpilot_diagnostic;
ALTER DEFAULT PRIVILEGES FOR ROLE lab_service IN SCHEMA public
    GRANT SELECT ON TABLES TO faultpilot_diagnostic;
SQL

