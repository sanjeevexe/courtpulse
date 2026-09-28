#!/usr/bin/env bash
# Invalidate CloudFront paths (default /index.html) and wait for completion.
#
#   CLOUDFRONT_DISTRIBUTION_ID=<id> scripts/aws/invalidate-web.sh [/index.html ...]
#
# The first 1,000 invalidation paths per month are free.
set -Eeuo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/aws/lib.sh
source "${script_dir}/lib.sh"

require_cmd aws
require_env CLOUDFRONT_DISTRIBUTION_ID

paths=("$@")
[[ ${#paths[@]} -gt 0 ]] || paths=("/index.html")

invalidation_id="$(aws cloudfront create-invalidation --distribution-id "$CLOUDFRONT_DISTRIBUTION_ID" \
  --paths "${paths[@]}" --query 'Invalidation.Id' --output text)"
log "Created invalidation ${invalidation_id} for ${paths[*]}"
aws cloudfront wait invalidation-completed --distribution-id "$CLOUDFRONT_DISTRIBUTION_ID" --id "$invalidation_id"
log "Invalidation ${invalidation_id} completed"
