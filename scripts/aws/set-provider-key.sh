#!/usr/bin/env bash
# Store the BALLDONTLIE API key in its Secrets Manager secret without echoing
# it, placing it on a command line, or writing it to disk.
#
#   scripts/aws/set-provider-key.sh                 # prompts (input hidden)
#   pbpaste | scripts/aws/set-provider-key.sh       # or read from a pipe
#
# Environment: AWS_REGION; optional PROVIDER_KEY_SECRET_ID (defaults to
# courtpulse-staging/balldontlie-api-key, the Terraform-created secret).
# The ingestor reads the value only at task start: after rotating, force a new
# ingestor deployment (docs/runbooks/secret-rotation.md).
set -Eeuo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/aws/lib.sh
source "${script_dir}/lib.sh"

require_cmd aws
require_env AWS_REGION
secret_id="${PROVIDER_KEY_SECRET_ID:-courtpulse-staging/balldontlie-api-key}"

if [[ -t 0 ]]; then
  read -r -s -p "BALLDONTLIE API key (input hidden): " provider_key
  printf '\n' >&2
else
  IFS= read -r provider_key || true
fi

# Strip surrounding whitespace and a trailing carriage return.
provider_key="${provider_key%$'\r'}"
provider_key="${provider_key#"${provider_key%%[![:space:]]*}"}"
provider_key="${provider_key%"${provider_key##*[![:space:]]}"}"
[[ -n "$provider_key" ]] || die "No key provided"
[[ ${#provider_key} -le 512 ]] || die "Key is implausibly long; refusing to store it"

# printf is a shell builtin, so the key never appears in the process list;
# the CLI reads it from stdin via file:///dev/stdin.
version_id="$(printf '%s' "$provider_key" | aws secretsmanager put-secret-value \
  --secret-id "$secret_id" --secret-string file:///dev/stdin \
  --query 'VersionId' --output text)"
unset provider_key

log "Stored a new version (${version_id}) of ${secret_id}"
log "If the ingestor is running: aws ecs update-service --cluster <cluster> --service <prefix>-ingestor --force-new-deployment"
