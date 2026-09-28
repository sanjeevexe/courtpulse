#!/usr/bin/env bash
# Shared helpers for the CourtPulse AWS scripts. Source it; do not execute it.
#
# Every script reads non-secret identifiers from the environment (the same
# names as the GitHub Actions variables in docs/deployment/aws-staging.md), so
# a human with AWS credentials can run exactly what CI runs. Nothing here
# prints secret values.
#
# Compatible with the macOS system bash (3.2): no associative arrays.

# shellcheck disable=SC2034 # Constants are used by the sourcing scripts.
readonly APP_CONTAINER="app"
readonly TAG_DEPLOYED_BY="courtpulse:deployed-by"
readonly TAG_IMAGE_TAG="courtpulse:image-tag"

log() {
  printf '[%s] %s\n' "$(date -u +%H:%M:%S)" "$*" >&2
}

die() {
  log "ERROR: $*"
  exit 1
}

require_cmd() {
  local cmd
  for cmd in "$@"; do
    command -v "$cmd" >/dev/null 2>&1 || die "Required command not found: $cmd"
  done
}

require_env() {
  local name
  for name in "$@"; do
    [[ -n "${!name:-}" ]] || die "Required environment variable is empty: $name"
  done
}

require_git_sha() {
  [[ "$1" =~ ^[0-9a-f]{40}$ ]] || die "Expected a full 40-character lowercase git SHA, got: '$1'"
}

# arn:aws:ecs:REGION:ACCOUNT:task-definition/FAMILY:REVISION -> FAMILY
family_of_arn() {
  local arn="$1" tail
  tail="${arn##*:task-definition/}"
  printf '%s\n' "${tail%:*}"
}

# ... -> REVISION
revision_of_arn() {
  printf '%s\n' "${1##*:}"
}

# Image tag of the application container in a task definition (ARN or family).
image_tag_of() {
  local image
  image="$(aws ecs describe-task-definition --task-definition "$1" \
    --query "taskDefinition.containerDefinitions[?name=='${APP_CONTAINER}'].image | [0]" \
    --output text)"
  [[ -n "$image" && "$image" != "None" ]] || die "No '${APP_CONTAINER}' container in $1"
  printf '%s\n' "${image##*:}"
}

current_task_definition() {
  local service="$1" arn
  arn="$(aws ecs describe-services --cluster "$ECS_CLUSTER" --services "$service" \
    --query 'services[0].taskDefinition' --output text)"
  [[ -n "$arn" && "$arn" != "None" ]] || die "Service not found in ${ECS_CLUSTER}: $service"
  printf '%s\n' "$arn"
}

# Register a new revision of FAMILY that is identical to the family's latest
# ACTIVE revision except for the application image tag. Terraform owns every
# other field; copying the latest revision carries Terraform configuration
# changes into the deploy. The revision is tagged with its provenance so
# rollback can find CI-built revisions. Prints the new revision ARN.
register_revision_with_image() {
  local family="$1" tag="$2" deployed_by="${3:-ci}"
  local source_json image_repo request_file arn

  source_json="$(aws ecs describe-task-definition --task-definition "$family" --include TAGS --output json)"
  image_repo="$(jq -r --arg c "$APP_CONTAINER" \
    '.taskDefinition.containerDefinitions[] | select(.name == $c) | .image' <<<"$source_json")"
  [[ -n "$image_repo" && "$image_repo" != "null" ]] || die "No '${APP_CONTAINER}' container in family $family"
  image_repo="${image_repo%@sha256:*}"
  image_repo="${image_repo%:*}"

  request_file="$(mktemp)"
  jq --arg c "$APP_CONTAINER" \
    --arg image "${image_repo}:${tag}" \
    --arg tag "$tag" \
    --arg by "$deployed_by" \
    --arg by_key "$TAG_DEPLOYED_BY" \
    --arg tag_key "$TAG_IMAGE_TAG" '
      (.tags // []) as $existing
      | .taskDefinition
      | .containerDefinitions |= map(if .name == $c then .image = $image else . end)
      | del(.taskDefinitionArn, .revision, .status, .requiresAttributes, .compatibilities,
            .registeredAt, .registeredBy, .deregisteredAt)
      | .tags = ([$existing[] | select(.key != $by_key and .key != $tag_key)]
                 + [{key: $by_key, value: $by}, {key: $tag_key, value: $tag}])
    ' <<<"$source_json" >"$request_file"

  arn="$(aws ecs register-task-definition --cli-input-json "file://${request_file}" \
    --query 'taskDefinition.taskDefinitionArn' --output text)"
  rm -f "$request_file"
  log "Registered ${arn##*/} (${deployed_by}, image tag ${tag:0:12})"
  printf '%s\n' "$arn"
}

# Newest ACTIVE revision of FAMILY registered by CI for IMAGE_TAG, or empty.
find_ci_revision_for_tag() {
  local family="$1" tag="$2" arn tags_json
  while read -r arn; do
    [[ -n "$arn" && "$arn" != "None" ]] || continue
    [[ "$(family_of_arn "$arn")" == "$family" ]] || continue
    tags_json="$(aws ecs describe-task-definition --task-definition "$arn" --include TAGS --query 'tags' --output json)"
    if jq -e --arg by_key "$TAG_DEPLOYED_BY" --arg tag_key "$TAG_IMAGE_TAG" --arg tag "$tag" '
        (. // []) as $tags
        | ($tags | map(select(.key == $by_key))[0].value // "") as $by
        | ($tags | map(select(.key == $tag_key))[0].value // "") as $t
        | ($by == "ci" or $by == "rollback") and $t == $tag' <<<"$tags_json" >/dev/null; then
      printf '%s\n' "$arn"
      return 0
    fi
  done < <(aws ecs list-task-definitions --family-prefix "$family" --status ACTIVE --sort DESC \
    --max-items "${ROLLBACK_SCAN_LIMIT:-60}" --query 'taskDefinitionArns[]' --output text | tr '\t' '\n')
  return 0
}

# Confirm IMAGE_TAG exists in the repository used by a task definition.
require_image_exists() {
  local task_definition="$1" tag="$2" image repository
  image="$(aws ecs describe-task-definition --task-definition "$task_definition" \
    --query "taskDefinition.containerDefinitions[?name=='${APP_CONTAINER}'].image | [0]" --output text)"
  repository="${image%:*}"
  repository="${repository#*/}"
  aws ecr describe-images --repository-name "$repository" --image-ids "imageTag=${tag}" \
    --query 'imageDetails[0].imageTags' --output text >/dev/null 2>&1 \
    || die "Image ${repository}:${tag} does not exist (it may have expired under the ECR lifecycle policy)"
}

# Wait for services to reach a steady state, then confirm that each one's
# PRIMARY deployment runs the expected revision and completed. A deployment
# circuit-breaker rollback also ends "stable", so stability alone is not proof.
# Arguments: service names, "--", then expected task-definition ARNs (same order).
wait_and_verify_services() {
  local timeout="${SERVICE_STABLE_TIMEOUT_SECONDS:-1500}"
  local services=() expected=() seen_separator=false arg
  for arg in "$@"; do
    if [[ "$arg" == "--" ]]; then
      seen_separator=true
    elif [[ "$seen_separator" == true ]]; then
      expected+=("$arg")
    else
      services+=("$arg")
    fi
  done
  [[ ${#services[@]} -gt 0 && ${#services[@]} -eq ${#expected[@]} ]] \
    || die "wait_and_verify_services needs matching service and task-definition lists"

  local deadline=$((SECONDS + timeout))
  log "Waiting up to ${timeout}s for ${#services[@]} service(s) to become stable"
  until aws ecs wait services-stable --cluster "$ECS_CLUSTER" --services "${services[@]}" 2>/dev/null; do
    ((SECONDS < deadline)) || die "Services did not stabilize within ${timeout}s"
    log "Still waiting for services to stabilize"
  done

  local index primary
  for index in "${!services[@]}"; do
    # shellcheck disable=SC2016 # Backticks are JMESPath literals, not shell expansion.
    primary="$(aws ecs describe-services --cluster "$ECS_CLUSTER" --services "${services[$index]}" \
      --query 'services[0].deployments[?status==`PRIMARY`] | [0].[taskDefinition, rolloutState]' --output text)"
    if [[ "$primary" != "${expected[$index]}"$'\t'"COMPLETED" ]]; then
      die "${services[$index]} is not running ${expected[$index]##*/} (primary deployment: ${primary}). The deployment circuit breaker may have rolled it back; inspect stopped tasks and logs."
    fi
    log "${services[$index]} is stable on ${expected[$index]##*/}"
  done
}

# Split the space-separated ECS_SERVICES variable into the named array.
read_services() {
  require_env ECS_SERVICES
  # shellcheck disable=SC2034 # Assigned by name for the caller.
  read -r -a "$1" <<<"$ECS_SERVICES"
}
