#!/usr/bin/env bash
# Point every ECS service at a new revision that runs IMAGE_TAG, then wait
# until each rolling deployment completes (not merely "stable": a circuit
# breaker rollback is also stable, and is reported as a failure).
#
#   scripts/aws/deploy-services.sh <git-sha>
#
# Environment: AWS_REGION, ECS_CLUSTER, ECS_SERVICES (space-separated),
# optional SERVICE_STABLE_TIMEOUT_SECONDS (default 1500).
set -Eeuo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/aws/lib.sh
source "${script_dir}/lib.sh"

tag="${1:-}"
require_git_sha "$tag"
require_cmd aws jq
require_env AWS_REGION ECS_CLUSTER

services=()
read_services services
[[ ${#services[@]} -gt 0 ]] || die "ECS_SERVICES is empty"

new_revisions=()
for service in "${services[@]}"; do
  family="$(family_of_arn "$(current_task_definition "$service")")"
  revision="$(register_revision_with_image "$family" "$tag")"
  aws ecs update-service --cluster "$ECS_CLUSTER" --service "$service" \
    --task-definition "$revision" --query 'service.serviceName' --output text >/dev/null
  log "Updated ${service} -> ${revision##*/}"
  new_revisions+=("$revision")
done

wait_and_verify_services "${services[@]}" -- "${new_revisions[@]}"
log "All services run image tag ${tag:0:12}"
