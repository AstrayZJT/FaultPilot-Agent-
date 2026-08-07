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
GRANT pg_read_all_stats TO faultpilot_diagnostic;
ALTER ROLE faultpilot_diagnostic SET default_transaction_read_only = on;
ALTER ROLE faultpilot_diagnostic SET statement_timeout = '3s';
SQL
