#!/usr/bin/env bash
# One-command local demo: the full stack with a simulated live provider, sign-in, personalized
# alerts, and email captured by Mailpit. Everything runs locally; nothing is sent anywhere.
#
#   scripts/demo.sh up        start or resume (first run builds images: several minutes)
#   scripts/demo.sh status    show services, URLs, and the demo usernames
#   scripts/demo.sh logs SVC  follow one service's logs
#   scripts/demo.sh import DATASET  import more real games (e.g. cdnnba_2025, the 2025-26 season)
#   scripts/demo.sh down      stop, keeping data
#   scripts/demo.sh destroy   stop and delete the demo's data volumes and images
#
# The demo uses its own Compose project (courtpulse-demo) and its own ignored settings file
# (.env.demo, generated on first use with random local-only passwords), so it never touches the
# default courtpulse project, its volumes, or your .env.
set -Eeuo pipefail

repository="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
env_file="${repository}/.env.demo"
project='courtpulse-demo'
export COURTPULSE_COMPOSE_PROJECT="${project}"

compose=(docker compose --project-directory "${repository}" -f "${repository}/compose.yaml"
  --env-file "${env_file}" -p "${project}"
  --profile auth --profile live --profile simulator --profile reconciliation --profile delivery
  --profile replay)

usage() {
  sed -n '2,14p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

require_docker() {
  if ! docker info >/dev/null 2>&1; then
    echo 'Docker is not running. Start Docker Desktop and try again.' >&2
    exit 1
  fi
}

# Copies .env.example and fills every blank value: fixed demo usernames, random passwords.
ensure_env() {
  [[ -f "${env_file}" ]] && return
  secret() { openssl rand -hex 16; }
  umask 077
  {
    while IFS= read -r line; do
      case "${line}" in
        COURTPULSE_IDP_ADMIN_USERNAME=) echo "COURTPULSE_IDP_ADMIN_USERNAME=admin" ;;
        COURTPULSE_TEST_USER_A=) echo "COURTPULSE_TEST_USER_A=fan-a" ;;
        COURTPULSE_TEST_USER_B=) echo "COURTPULSE_TEST_USER_B=fan-b" ;;
        COURTPULSE_TEST_OPS_USER=) echo "COURTPULSE_TEST_OPS_USER=ops" ;;
        *_PASSWORD=) echo "${line}$(secret)" ;;
        *) echo "${line}" ;;
      esac
    done <"${repository}/.env.example"
    echo
    echo '# Demo database password (local only).'
    echo "COURTPULSE_DB_PASSWORD=$(secret)"
    echo '# Demo pacing: tip-off two minutes after start (time to sign in and add a rule), then'
    echo '# about 13 minutes of simulated play at 20x.'
    echo 'COURTPULSE_SIMULATOR_SPEED=20'
    echo 'COURTPULSE_SIMULATOR_START_DELAY_SECONDS=120'
  } >"${env_file}"
  echo "Created ${env_file} with local-only demo credentials."
}

# Reads KEY=value lines literally (as Compose does) instead of evaluating the file as shell.
load_env() {
  local key value
  while IFS='=' read -r key value; do
    [[ "${key}" =~ ^[A-Z_][A-Z0-9_]*$ ]] || continue
    export "${key}=${value}"
  done <"${env_file}"
}

urls() {
  cat <<EOF

CourtPulse demo is running:
  Dashboard        http://127.0.0.1:4173   (use 127.0.0.1, not localhost, for sign-in)
  Replay real games http://127.0.0.1:4173/replays
  API              http://127.0.0.1:8080/api/v1/games
  Mailpit (email)  http://127.0.0.1:8025
  Keycloak admin   http://127.0.0.1:8180   (user: admin)

Sign in as ${COURTPULSE_TEST_USER_A} or ${COURTPULSE_TEST_USER_B} (fans) or ${COURTPULSE_TEST_OPS_USER} (operations).
Passwords are in .env.demo. The simulated game (Harbor City Herons vs Summit Valley Sentinels)
tips off about two minutes after the first start, plays through overtime, and receives a scorer
correction. Each demo plays it once; for a fresh one: scripts/demo.sh destroy, then up.
EOF
}

up() {
  require_docker
  ensure_env
  load_env
  echo 'Building images (the first run takes several minutes)...'
  "${compose[@]}" build --quiet
  "${compose[@]}" up -d --wait postgres localstack >/dev/null
  "${compose[@]}" run --rm -T --no-deps processor-worker --migrate >/dev/null
  # Seed the recorded replay fixture (a finished game) only into an empty database, before the
  # ingestor starts: --reset-import truncates game data, so it must never run on a used demo.
  local games
  games="$("${compose[@]}" exec -T postgres psql -At -U courtpulse -d courtpulse \
    -c 'SELECT count(*) FROM games')"
  if [[ "${games}" == 0 ]]; then
    "${compose[@]}" run --rm -T --no-deps processor-worker --reset-import --publish >/dev/null
  fi
  # Real games for replay: the 2026 playoffs (about 1 MB) on first start, if the network allows.
  local catalog
  catalog="$("${compose[@]}" exec -T postgres psql -At -U courtpulse -d courtpulse \
    -c 'SELECT count(*) FROM replay_catalog')"
  if [[ "${catalog}" == 0 ]]; then
    echo 'Importing real NBA playoff games for replay...'
    "${compose[@]}" run --rm -T --no-deps processor-worker --replay-import=cdnnba_po_2025 2>/dev/null \
      | grep 'Replay catalog import' || echo 'Could not download replay games now; run: scripts/demo.sh import cdnnba_po_2025'
  fi
  echo 'Starting services...'
  "${compose[@]}" up -d --wait >/dev/null
  if ! "${repository}/scripts/provision-local-identity.sh" >/dev/null 2>&1; then
    echo 'Provisioning the demo users failed; run scripts/demo.sh up again.' >&2
    exit 1
  fi
  urls
}

case "${1:-}" in
  up) up ;;
  status)
    require_docker
    [[ -f "${env_file}" ]] || { echo 'No demo yet; run: scripts/demo.sh up' >&2; exit 1; }
    load_env
    "${compose[@]}" ps
    urls
    ;;
  import)
    [[ -n "${2:-}" ]] || { echo 'Usage: scripts/demo.sh import DATASET (e.g. cdnnba_po_2025)' >&2; exit 2; }
    load_env
    "${compose[@]}" run --rm -T --no-deps processor-worker --replay-import="$2"
    ;;
  logs)
    [[ -n "${2:-}" ]] || { echo 'Usage: scripts/demo.sh logs SERVICE' >&2; exit 2; }
    load_env
    "${compose[@]}" logs -f "$2"
    ;;
  down)
    load_env
    "${compose[@]}" down
    ;;
  destroy)
    load_env
    "${compose[@]}" down -v --rmi local --remove-orphans
    echo "Removed the ${project} project. .env.demo was kept; delete it to start fresh."
    ;;
  *) usage; exit 2 ;;
esac
