#!/usr/bin/env bash
# Roll every ECS service back to an earlier image.
#
#   scripts/aws/rollback.sh resolve [git-sha]   # print the tag to roll back to
#   scripts/aws/rollback.sh apply <git-sha>     # re-point every service to it
#
# resolve without an argument returns the image tag of the newest CI-deployed
# API revision older than the one currently running with a different tag
# (normally "the previous deploy"). With an argument it only validates that
# the tag exists in ECR.
#
# apply re-points each service to the newest CI-registered revision for that
# tag (the exact task definition that ran before, including its configuration).
# If no such revision remains, it registers one from the latest template with
# that image. Migrations are NOT reverted: CourtPulse uses expand-and-contract
# migrations so the previous release keeps working on the newer schema (see
# docs/runbooks/rollback.md).
#
# Environment: AWS_REGION, ECS_CLUSTER, ECS_SERVICES, optional ECS_API_SERVICE,
# SERVICE_STABLE_TIMEOUT_SECONDS, ROLLBACK_SCAN_LIMIT.
set -Eeuo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/aws/lib.sh
source "${script_dir}/lib.sh"

require_cmd aws jq
require_env AWS_REGION ECS_CLUSTER

services=()
read_services services
[[ ${#services[@]} -gt 0 ]] || die "ECS_SERVICES is empty"

api_service() {
  if [[ -n "${ECS_API_SERVICE:-}" ]]; then
    printf '%s\n' "$ECS_API_SERVICE"
    return 0
  fi
  local service
  for service in "${services[@]}"; do
    if [[ "$service" == *-api ]]; then
      printf '%s\n' "$service"
      return 0
    fi
  done
  die "No service ending in -api in ECS_SERVICES; set ECS_API_SERVICE"
}

resolve_previous_tag() {
  local current family current_revision current_tag arn tags_json candidate
  current="$(current_task_definition "$(api_service)")"
  family="$(family_of_arn "$current")"
  current_revision="$(revision_of_arn "$current")"
  current_tag="$(image_tag_of "$current")"
  log "API currently runs ${current##*/} (image tag ${current_tag:0:12})"

  while read -r arn; do
    [[ -n "$arn" && "$arn" != "None" ]] || continue
    [[ "$(family_of_arn "$arn")" == "$family" ]] || continue
    (($(revision_of_arn "$arn") < current_revision)) || continue
    tags_json="$(aws ecs describe-task-definition --task-definition "$arn" --include TAGS --query 'tags' --output json)"
    candidate="$(jq -r --arg by_key "$TAG_DEPLOYED_BY" --arg tag_key "$TAG_IMAGE_TAG" '
        (. // []) as $tags
        | if ($tags | map(select(.key == $by_key))[0].value // "") == "ci"
          then ($tags | map(select(.key == $tag_key))[0].value // "")
          else "" end' <<<"$tags_json")"
    if [[ "$candidate" =~ ^[0-9a-f]{40}$ && "$candidate" != "$current_tag" ]]; then
      log "Previous CI deployment: ${arn##*/} (image tag ${candidate:0:12})"
      printf '%s\n' "$candidate"
      return 0
    fi
  done < <(aws ecs list-task-definitions --family-prefix "$family" --status ACTIVE --sort DESC \
    --max-items "${ROLLBACK_SCAN_LIMIT:-60}" --query 'taskDefinitionArns[]' --output text | tr '\t' '\n')

  die "No earlier CI-deployed revision with a different image was found for ${family}"
}

command="${1:-}"
case "$command" in
  resolve)
    requested="${2:-}"
    if [[ -z "$requested" ]]; then
      resolve_previous_tag
    else
      require_git_sha "$requested"
      require_image_exists "$(current_task_definition "$(api_service)")" "$requested"
      printf '%s\n' "$requested"
    fi
    ;;
  apply)
    tag="${2:-}"
    require_git_sha "$tag"
    targets=()
    for service in "${services[@]}"; do
      current="$(current_task_definition "$service")"
      family="$(family_of_arn "$current")"
      target="$(find_ci_revision_for_tag "$family" "$tag")"
      if [[ -z "$target" ]]; then
        log "No CI revision of ${family} for ${tag:0:12}; registering one from the latest template"
        require_image_exists "$current" "$tag"
        target="$(register_revision_with_image "$family" "$tag" rollback)"
      fi
      if [[ "$target" == "$current" ]]; then
        log "${service} already runs ${target##*/}"
      else
        aws ecs update-service --cluster "$ECS_CLUSTER" --service "$service" \
          --task-definition "$target" --query 'service.serviceName' --output text >/dev/null
        log "Re-pointed ${service}: ${current##*/} -> ${target##*/}"
      fi
      targets+=("$target")
    done
    wait_and_verify_services "${services[@]}" -- "${targets[@]}"
    log "Rollback to ${tag:0:12} complete"
    ;;
  *)
    die "Usage: $0 resolve [git-sha] | apply <git-sha>"
    ;;
esac
