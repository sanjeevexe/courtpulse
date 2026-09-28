#!/usr/bin/env bash
# Post-deploy smoke test through CloudFront (the only public entry point).
#
#   scripts/aws/smoke-test.sh https://d111111abcdef8.cloudfront.net
#
# Checks: SPA shell and deep link, security headers on HTML and API
# responses, public game list JSON, Cognito-backed auth configuration,
# fail-closed protected route, and the canary's synthetic game.
set -Eeuo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/aws/lib.sh
source "${script_dir}/lib.sh"

require_cmd curl jq
base_url="${1:-${CLOUDFRONT_URL:-}}"
base_url="${base_url%/}"
[[ "$base_url" =~ ^https://[^/]+$ ]] || die "Usage: $0 https://<distribution-domain> (or set CLOUDFRONT_URL)"
canary_game="${SMOKE_CANARY_GAME_ID:-game_synthetic_001}"

work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT
failures=0

fail() {
  log "FAIL: $*"
  failures=$((failures + 1))
}

# fetch PATH -> sets status; body in $work_dir/body, headers in $work_dir/headers
fetch() {
  status="$(curl --silent --show-error --proto '=https' --tlsv1.2 --max-time 20 \
    --output "${work_dir}/body" --dump-header "${work_dir}/headers" \
    --write-out '%{http_code}' "${base_url}$1" || true)"
}

header_value() {
  grep -i "^$1:" "${work_dir}/headers" | tail -n 1 | cut -d: -f2- | tr -d '\r' | sed 's/^ *//'
}

expect_status() {
  local path="$1" want_status="$2" attempt
  for attempt in 1 2 3 4 5 6; do
    fetch "$path"
    [[ "$status" == "$want_status" ]] && return 0
    log "${path}: HTTP ${status}, expected ${want_status} (attempt ${attempt}/6)"
    sleep 10
  done
  fail "${path} returned HTTP ${status}, expected ${want_status}"
  return 1
}

expect_security_headers() {
  local label="$1" csp
  csp="$(header_value content-security-policy)"
  [[ "$csp" == *"default-src 'self'"* && "$csp" == *"frame-ancestors 'none'"* && "$csp" == *"script-src 'self'"* ]] \
    || fail "${label}: Content-Security-Policy missing or weak: '${csp}'"
  [[ "$(header_value strict-transport-security)" == *"max-age="* ]] || fail "${label}: Strict-Transport-Security missing"
  [[ "$(header_value x-content-type-options)" == "nosniff" ]] || fail "${label}: X-Content-Type-Options is not nosniff"
  [[ "$(header_value x-frame-options)" == "DENY" ]] || fail "${label}: X-Frame-Options is not DENY"
  [[ "$(header_value referrer-policy)" == "strict-origin-when-cross-origin" ]] || fail "${label}: Referrer-Policy mismatch"
}

log "Smoke testing ${base_url}"

if expect_status "/" 200; then
  [[ "$(header_value content-type)" == text/html* ]] || fail "/ is not text/html"
  expect_security_headers "/"
fi

if expect_status "/games/${canary_game}" 200; then
  [[ "$(header_value content-type)" == text/html* ]] || fail "SPA deep link was not rewritten to index.html"
fi

if expect_status "/api/v1/games?limit=5" 200; then
  [[ "$(header_value content-type)" == application/json* ]] || fail "/api/v1/games is not JSON"
  jq -e '.items | type == "array"' "${work_dir}/body" >/dev/null || fail "/api/v1/games has no items array"
  expect_security_headers "/api/v1/games"
fi

if expect_status "/api/v1/auth/config" 200; then
  jq -e '.enabled == true and (.issuer | startswith("https://cognito-idp.")) and (.clientId | length > 0)' \
    "${work_dir}/body" >/dev/null || fail "/api/v1/auth/config does not describe enabled Cognito authentication"
fi

# Protected resources fail closed for anonymous callers.
expect_status "/api/v1/me" 401 || true

if [[ "${SMOKE_EXPECT_CANARY_GAME:-true}" == "true" ]]; then
  expect_status "/api/v1/games/${canary_game}" 200 || true
fi

if ((failures > 0)); then
  die "${failures} smoke check(s) failed"
fi
log "All smoke checks passed"
