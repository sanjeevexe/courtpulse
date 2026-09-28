#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
runtime_dir="$repo_root/build/observability"
for name in postgres-password metrics-password grafana-password; do
  if [[ ! -s "$runtime_dir/$name" ]]; then
    echo "Missing local observability runtime: run scripts/start-observability.sh first" >&2
    exit 1
  fi
done

COURTPULSE_DB_PASSWORD="$(tr -d '\n' < "$runtime_dir/postgres-password")"
COURTPULSE_METRICS_SCRAPE_PASSWORD="$(tr -d '\n' < "$runtime_dir/metrics-password")"
COURTPULSE_GRAFANA_ADMIN_PASSWORD="$(tr -d '\n' < "$runtime_dir/grafana-password")"
export COURTPULSE_DB_PASSWORD COURTPULSE_METRICS_SCRAPE_PASSWORD COURTPULSE_GRAFANA_ADMIN_PASSWORD
export COURTPULSE_METRICS_SCRAPE_PASSWORD_FILE="$runtime_dir/metrics-password"
export COURTPULSE_POSTGRES_HOST_PORT="${COURTPULSE_POSTGRES_HOST_PORT:-15432}"
export COURTPULSE_LOCALSTACK_HOST_PORT="${COURTPULSE_LOCALSTACK_HOST_PORT:-14566}"
export COURTPULSE_API_HOST_PORT="${COURTPULSE_API_HOST_PORT:-18080}"
export COURTPULSE_WEB_HOST_PORT="${COURTPULSE_WEB_HOST_PORT:-14173}"
export COURTPULSE_MAILPIT_SMTP_HOST_PORT="${COURTPULSE_MAILPIT_SMTP_HOST_PORT:-11025}"
export COURTPULSE_MAILPIT_UI_HOST_PORT="${COURTPULSE_MAILPIT_UI_HOST_PORT:-18025}"
export COURTPULSE_GRAFANA_HOST_PORT="${COURTPULSE_GRAFANA_HOST_PORT:-13000}"

cd "$repo_root"
project="${COURTPULSE_OBSERVABILITY_PROJECT:-courtpulse-observability}"
if [[ ! "$project" =~ ^courtpulse-observability(-[a-z0-9]+)?$ ]]; then
  echo "Observability project must use the courtpulse-observability prefix" >&2
  exit 1
fi
exec docker compose -p "$project" \
  -f compose.yaml -f compose.observability.yaml \
  --profile delivery --profile reconciliation --profile observability "$@"
