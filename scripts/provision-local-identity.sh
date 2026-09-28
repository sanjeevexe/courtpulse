#!/usr/bin/env bash
set -Eeuo pipefail

repository="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

required=(
  COURTPULSE_IDP_ADMIN_USERNAME COURTPULSE_IDP_ADMIN_PASSWORD
  COURTPULSE_TEST_USER_A COURTPULSE_TEST_USER_A_PASSWORD
  COURTPULSE_TEST_USER_B COURTPULSE_TEST_USER_B_PASSWORD
  COURTPULSE_TEST_OPS_USER COURTPULSE_TEST_OPS_PASSWORD
)
for name in "${required[@]}"; do
  if [[ -z "${!name:-}" ]]; then
    echo "${name} is required" >&2
    exit 2
  fi
done

compose=(docker compose --project-directory "${repository}" -f "${repository}/compose.yaml")
if [[ -n "${COURTPULSE_COMPOSE_PROJECT:-}" ]]; then
  compose+=(-p "${COURTPULSE_COMPOSE_PROJECT}")
fi
kcadm=("${compose[@]}" --profile auth exec -T identity /opt/keycloak/bin/kcadm.sh)

"${kcadm[@]}" config credentials \
  --server http://127.0.0.1:8080 --realm master \
  --user "${COURTPULSE_IDP_ADMIN_USERNAME}" --password "${COURTPULSE_IDP_ADMIN_PASSWORD}"

# Idempotent: an existing user keeps its ID and only has its password reset.
provision_user() {
  local username="$1"
  local password="$2"
  if [[ -n "$("${kcadm[@]}" get users -r courtpulse -q "username=${username}" -q exact=true \
      --fields id --format csv --noquotes)" ]]; then
    "${kcadm[@]}" set-password -r courtpulse --username "${username}" \
      --new-password "${password}" --temporary=false
    return
  fi
  "${kcadm[@]}" create users -r courtpulse \
    -s "username=${username}" \
    -s "email=${username}@example.invalid" \
    -s firstName=CourtPulse \
    -s lastName=Acceptance \
    -s emailVerified=true \
    -s enabled=true >/dev/null
  "${kcadm[@]}" set-password -r courtpulse --username "${username}" \
    --new-password "${password}" --temporary=false
}

provision_user "${COURTPULSE_TEST_USER_A}" "${COURTPULSE_TEST_USER_A_PASSWORD}"
provision_user "${COURTPULSE_TEST_USER_B}" "${COURTPULSE_TEST_USER_B_PASSWORD}"
provision_user "${COURTPULSE_TEST_OPS_USER}" "${COURTPULSE_TEST_OPS_PASSWORD}"
"${kcadm[@]}" add-roles -r courtpulse \
  --uusername "${COURTPULSE_TEST_OPS_USER}" --rolename 'courtpulse:ops'

echo 'Local CourtPulse test identities provisioned from environment variables.'
