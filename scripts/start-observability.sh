#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
runtime_dir="$repo_root/build/observability"
mkdir -p "$runtime_dir"
chmod 700 "$runtime_dir"

secret_file() {
  local path="$1"
  if [[ ! -s "$path" ]]; then
    openssl rand -hex 32 > "$path"
    chmod 600 "$path"
  fi
}

secret_file "$runtime_dir/postgres-password"
secret_file "$runtime_dir/metrics-password"
secret_file "$runtime_dir/grafana-password"

# The pinned OpenTelemetry Java agent is baked into the API and worker images with a
# Dockerfile SHA-256 check; this overlay only enables it through JAVA_TOOL_OPTIONS.
"$repo_root/scripts/observability-compose.sh" up -d --build

echo "CourtPulse observability stack is at http://127.0.0.1:${COURTPULSE_GRAFANA_HOST_PORT:-13000}"
echo "Grafana user: courtpulse; local password file: $runtime_dir/grafana-password"
echo "This isolated Compose project has its own volumes; no normal project volumes were changed."
