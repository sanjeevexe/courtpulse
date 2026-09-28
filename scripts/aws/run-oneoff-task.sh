#!/usr/bin/env bash
# Run the one-off migrate or canary task for an image tag and fail unless the
# application container exits 0. Its CloudWatch log is printed either way.
#
#   scripts/aws/run-oneoff-task.sh migrate <git-sha>
#   scripts/aws/run-oneoff-task.sh canary  <git-sha>
#
# Environment: AWS_REGION, ECS_CLUSTER, MIGRATE_TASK_FAMILY, CANARY_TASK_FAMILY,
# TASK_SUBNETS (comma-separated public subnets), TASK_SECURITY_GROUP,
# ONEOFF_LOG_GROUP, optional ONEOFF_TIMEOUT_SECONDS (default 900).
#
# migrate: Flyway migrations, then exit 0 (the PostgreSQL advisory lock makes a
#          concurrent API start safe).
# canary:  idempotently imports the synthetic fixture and waits until the
#          processor service has produced its known checksum.
set -Eeuo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/aws/lib.sh
source "${script_dir}/lib.sh"

kind="${1:-}"
tag="${2:-}"
require_git_sha "$tag"
require_cmd aws jq
require_env AWS_REGION ECS_CLUSTER TASK_SUBNETS TASK_SECURITY_GROUP ONEOFF_LOG_GROUP

case "$kind" in
  migrate)
    require_env MIGRATE_TASK_FAMILY
    family="$MIGRATE_TASK_FAMILY"
    ;;
  canary)
    require_env CANARY_TASK_FAMILY
    family="$CANARY_TASK_FAMILY"
    ;;
  *) die "Usage: $0 migrate|canary <git-sha>" ;;
esac

timeout="${ONEOFF_TIMEOUT_SECONDS:-900}"
task_definition="$(register_revision_with_image "$family" "$tag")"
network="awsvpcConfiguration={subnets=[${TASK_SUBNETS}],securityGroups=[${TASK_SECURITY_GROUP}],assignPublicIp=ENABLED}"

run_output="$(aws ecs run-task \
  --cluster "$ECS_CLUSTER" \
  --task-definition "$task_definition" \
  --launch-type FARGATE \
  --platform-version LATEST \
  --count 1 \
  --started-by "courtpulse-${kind}-${tag:0:12}" \
  --propagate-tags TASK_DEFINITION \
  --network-configuration "$network" \
  --output json)"

if [[ "$(jq '.failures | length' <<<"$run_output")" != "0" ]]; then
  die "run-task failed: $(jq -c '.failures' <<<"$run_output")"
fi
task_arn="$(jq -r '.tasks[0].taskArn' <<<"$run_output")"
task_id="${task_arn##*/}"
log "Started ${kind} task ${task_id}; waiting up to ${timeout}s"

print_logs() {
  local stream="${kind}/${APP_CONTAINER}/${task_id}"
  log "---- ${ONEOFF_LOG_GROUP} ${stream} (last 200 lines) ----"
  aws logs get-log-events --log-group-name "$ONEOFF_LOG_GROUP" --log-stream-name "$stream" \
    --start-from-head --limit 1000 --output json 2>/dev/null \
    | jq -r '.events[].message' | tail -n 200 || log "(no log events available)"
  log "---- end of ${kind} log ----"
}

deadline=$((SECONDS + timeout))
until aws ecs wait tasks-stopped --cluster "$ECS_CLUSTER" --tasks "$task_arn" 2>/dev/null; do
  if ((SECONDS >= deadline)); then
    aws ecs stop-task --cluster "$ECS_CLUSTER" --task "$task_arn" \
      --reason "courtpulse ${kind} exceeded ${timeout}s" >/dev/null || true
    print_logs
    die "${kind} task ${task_id} did not finish within ${timeout}s and was stopped"
  fi
  log "${kind} task ${task_id} still running"
done

result="$(aws ecs describe-tasks --cluster "$ECS_CLUSTER" --tasks "$task_arn" --output json)"
exit_code="$(jq -r --arg c "$APP_CONTAINER" '.tasks[0].containers[] | select(.name == $c) | .exitCode // "none"' <<<"$result")"
stop_code="$(jq -r '.tasks[0].stopCode // "unknown"' <<<"$result")"
stopped_reason="$(jq -r '.tasks[0].stoppedReason // ""' <<<"$result")"

print_logs

if [[ "$exit_code" != "0" ]]; then
  die "${kind} task ${task_id} failed: exitCode=${exit_code} stopCode=${stop_code} reason='${stopped_reason}'"
fi
log "${kind} task ${task_id} succeeded"
