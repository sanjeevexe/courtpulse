#!/usr/bin/env bash
# One-command local demo: the full stack replaying real 2026 NBA playoff games as live games, with
# sign-in, personalized alerts, and email captured by Mailpit. Everything runs locally; nothing is
# sent anywhere except the one-time download of the public play-by-play datasets.
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

# The live profile supplies the processor; its provider ingestor stays off (scaled to zero), so the
# demo shows only real replayed games. The live provider path is covered by verify-milestone-12.
compose=(docker compose --project-directory "${repository}" -f "${repository}/compose.yaml"
  --env-file "${env_file}" -p "${project}"
  --profile auth --profile live --profile reconciliation --profile delivery --profile replay)
up_args=(up -d --wait --scale ingestor-worker=0)

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
  Replay real games http://127.0.0.1:4173/replays   (all 85 games of the 2026 playoffs)
  API              http://127.0.0.1:8080/api/v1/games
  Mailpit (email)  http://127.0.0.1:8025
  Keycloak admin   http://127.0.0.1:8180   (user: admin)

Sign in as ${COURTPULSE_TEST_USER_A} or ${COURTPULSE_TEST_USER_B} (fans) or ${COURTPULSE_TEST_OPS_USER} (operations).
Passwords are in .env.demo. Sign in, open Replays, and start any game: it plays out live on the
scoreboard, and alert rules you create for it fire in the app and by email.
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
  # Real games for replay, once each (about 2 MB): the 2026 playoffs from NBA.com live data (real
  # timestamps, through May 9), then stats.nba.com play-by-play for the rest through the Finals.
  local dataset count
  for dataset in cdnnba_po_2025 nbastatsv3_po_2025; do
    count="$("${compose[@]}" exec -T postgres psql -At -U courtpulse -d courtpulse \
      -c "SELECT count(*) FROM replay_catalog WHERE dataset = '${dataset}'")"
    if [[ "${count}" == 0 ]]; then
      echo "Importing real NBA playoff games for replay (${dataset})..."
      "${compose[@]}" run --rm -T --no-deps processor-worker --replay-import="${dataset}" 2>/dev/null \
        | grep 'Replay catalog import' || echo "Could not download ${dataset} now; run: scripts/demo.sh import ${dataset}"
    fi
  done
  echo 'Starting services...'
  "${compose[@]}" "${up_args[@]}" >/dev/null
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
