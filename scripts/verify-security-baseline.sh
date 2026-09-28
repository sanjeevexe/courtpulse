#!/usr/bin/env bash
# Dynamic OWASP ZAP baseline (passive) scan of the production-shaped web boundary: Nginx, the SPA,
# and the same-origin /api proxy, with the seeded game. Runs in an isolated Compose project and
# writes HTML/JSON reports under build/verification/security/.
set -Eeuo pipefail

repository="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
suffix="$(openssl rand -hex 4)"
project="courtpulse-security-${suffix}"
artifact_dir="${repository}/build/verification/security/${project}"
mkdir -p "${artifact_dir}"
chmod 777 "${artifact_dir}" # the ZAP container writes its reports as a non-root user

COURTPULSE_DB_PASSWORD="$(openssl rand -hex 18)"
export COURTPULSE_DB_PASSWORD
export COURTPULSE_POSTGRES_HOST_PORT=57833
export COURTPULSE_LOCALSTACK_HOST_PORT=56967
export COURTPULSE_API_HOST_PORT=60581
export COURTPULSE_WEB_HOST_PORT=56673
export COURTPULSE_DB_URL="jdbc:postgresql://localhost:${COURTPULSE_POSTGRES_HOST_PORT}/courtpulse"
export COURTPULSE_SQS_ENDPOINT="http://localhost:${COURTPULSE_LOCALSTACK_HOST_PORT}"
zap_image='ghcr.io/zaproxy/zaproxy:stable'

compose=(docker compose --project-directory "${repository}" -f "${repository}/compose.yaml" -p "${project}")

finish() {
  local status=$?
  "${compose[@]}" logs --no-color >"${artifact_dir}/compose.log" 2>&1 || true
  "${compose[@]}" down -v --rmi local --remove-orphans >"${artifact_dir}/compose-down.txt" 2>&1 || true
  [[ ${status} -eq 0 ]] || echo "Security baseline failed; evidence is in ${artifact_dir}" >&2
  exit "${status}"
}
trap finish EXIT

cd "${repository}"
"${compose[@]}" up -d --wait postgres localstack >"${artifact_dir}/infrastructure-up.txt" 2>&1
"${repository}/gradlew" :apps:queue-replay-cli:run --args='--reset-import --run' --console=plain \
  >"${artifact_dir}/seed.txt"
"${compose[@]}" up -d --build --wait api web >"${artifact_dir}/application-up.txt" 2>&1

# -I: warnings are reported but only FAIL-level rules (or rules raised in .zap/rules.tsv) fail.
set +e
docker run --rm --network "${project}_default" -v "${artifact_dir}:/zap/wrk:rw" \
  -v "${repository}/.zap/rules.tsv:/zap/rules.tsv:ro" "${zap_image}" \
  zap-baseline.py -t http://web:8080 -c /zap/rules.tsv -r zap-report.html -J zap-report.json -m 2 -I \
  >"${artifact_dir}/zap-output.txt" 2>&1
zap_status=$?
set -e
tail -n 25 "${artifact_dir}/zap-output.txt"
if [[ ${zap_status} -ne 0 ]]; then
  echo "ZAP baseline reported failures (exit ${zap_status})" >&2
  exit 1
fi
echo "ZAP baseline passed; report: ${artifact_dir}/zap-report.html"
