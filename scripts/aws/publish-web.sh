#!/usr/bin/env bash
# Upload the Vite build to the private SPA bucket.
#
#   WEB_BUCKET=<bucket> scripts/aws/publish-web.sh apps/web/dist
#
# Order matters: new hashed assets first (immutable, one-year cache), then the
# SPA shell (no-cache), then pruning of assets the new build no longer uses.
# Pruning can break a tab still running the previous build if it lazily loads
# a removed chunk; a reload fixes it.
set -Eeuo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/aws/lib.sh
source "${script_dir}/lib.sh"

dist="${1:-apps/web/dist}"
require_cmd aws
require_env WEB_BUCKET
[[ -f "${dist}/index.html" ]] || die "${dist}/index.html not found; run npm run build in apps/web first"
[[ -d "${dist}/assets" ]] || die "${dist}/assets not found"

bucket="s3://${WEB_BUCKET}"
immutable="public, max-age=31536000, immutable"

log "Uploading hashed assets"
aws s3 sync "${dist}/assets" "${bucket}/assets" --only-show-errors --cache-control "$immutable"

log "Uploading the SPA shell and root files"
aws s3 sync "$dist" "$bucket" --only-show-errors --delete --exclude "assets/*" --cache-control "no-cache"
# Always overwrite index.html: sync compares size and time, and a new build can
# produce an index.html of identical size.
aws s3 cp "${dist}/index.html" "${bucket}/index.html" --only-show-errors \
  --cache-control "no-cache" --content-type "text/html; charset=utf-8"

log "Pruning assets that the new build no longer references"
aws s3 sync "${dist}/assets" "${bucket}/assets" --only-show-errors --delete --cache-control "$immutable"
log "Published ${dist} to ${bucket}"
