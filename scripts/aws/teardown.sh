#!/usr/bin/env bash
# Destroy the CourtPulse staging environment so it stops costing money.
#
#   scripts/aws/teardown.sh
#
# What happens, with a confirmation before each billed or irreversible step:
#   1. Shows the AWS identity and asks you to type the environment name.
#   2. terraform apply with deletion_protection=false and ecr_force_delete=true
#      (RDS and the Cognito pool refuse deletion while protected; ECR refuses
#      to delete repositories that still hold images unless force_delete is
#      already in state). Terraform prints the plan and asks for approval.
#   3. terraform destroy with the same variables (Terraform asks again).
#      The SPA bucket is emptied automatically (web_bucket_force_destroy).
#   4. Optionally deletes leftover task-definition revisions (free, but tidy).
#   5. Lists what intentionally survives and what it costs.
#
# Unless skip_final_snapshot=true in terraform.tfvars, RDS keeps a final
# snapshot (<prefix>-postgres-final) that is billed as snapshot storage until
# you delete it. The Terraform state bucket (bootstrap) is never touched here.
set -Eeuo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/aws/lib.sh
source "${script_dir}/lib.sh"

require_cmd aws terraform git jq
repo_root="$(git -C "$script_dir" rev-parse --show-toplevel)"
tf_dir="${TF_DIR:-${repo_root}/infra/terraform/environments/staging}"
prefix="${NAME_PREFIX:-courtpulse-staging}"
db_identifier="${DB_IDENTIFIER:-${prefix}-postgres}"
teardown_vars=(-var=deletion_protection=false -var=ecr_force_delete=true)

confirm() {
  local answer
  read -r -p "$1 [y/N] " answer
  [[ "$answer" == "y" || "$answer" == "Y" ]]
}

log "AWS identity: $(aws sts get-caller-identity --query '[Account, Arn]' --output text)"
log "Terraform root: ${tf_dir}"
cat >&2 <<TEXT

This permanently deletes the ${prefix} VPC, ALB, CloudFront distribution,
ECS cluster and services, RDS instance (automated backups included),
SQS queues and their messages, Cognito users, ECR images, secrets
(after their recovery window), alarms, dashboard, budget, and schedules.

TEXT
read -r -p "Type '${prefix}' to continue: " typed
[[ "$typed" == "$prefix" ]] || die "Confirmation did not match; nothing was changed"

# A stopped instance (nightly shutdown) cannot be modified, so start it first.
db_status="$(aws rds describe-db-instances --db-instance-identifier "$db_identifier" \
  --query 'DBInstances[0].DBInstanceStatus' --output text 2>/dev/null || echo "absent")"
if [[ "$db_status" == "stopped" ]]; then
  confirm "RDS is stopped and must be running to change deletion protection. Start it now (billed while running)?" \
    || die "Cancelled; nothing was changed"
  aws rds start-db-instance --db-instance-identifier "$db_identifier" --query 'DBInstance.DBInstanceStatus' --output text >/dev/null
  aws rds wait db-instance-available --db-instance-identifier "$db_identifier"
fi

confirm "Step 1/3: disable deletion protection and allow ECR force-delete (terraform apply)?" \
  || die "Cancelled before any change"
terraform -chdir="$tf_dir" apply "${teardown_vars[@]}"

confirm "Step 2/3: run terraform destroy?" || die "Cancelled; protection is now off - re-apply with deletion_protection=true to restore it"
terraform -chdir="$tf_dir" destroy "${teardown_vars[@]}"

if confirm "Step 3/3: delete leftover ECS task-definition revisions for ${prefix}-* (they are free but clutter the account)?"; then
  active_arns=()
  while read -r arn; do
    [[ -n "$arn" && "$arn" != "None" ]] && active_arns+=("$arn")
  done < <(aws ecs list-task-definitions --family-prefix "${prefix}-" --status ACTIVE \
    --query 'taskDefinitionArns[]' --output text | tr '\t' '\n')
  if ((${#active_arns[@]} > 0)); then
    for arn in "${active_arns[@]}"; do
      aws ecs deregister-task-definition --task-definition "$arn" --query 'taskDefinition.revision' --output text >/dev/null
    done
  fi
  while read -r arn; do
    [[ -n "$arn" && "$arn" != "None" ]] || continue
    aws ecs delete-task-definitions --task-definitions "$arn" --query 'failures' --output text >/dev/null || true
  done < <(aws ecs list-task-definitions --family-prefix "${prefix}-" --status INACTIVE \
    --query 'taskDefinitionArns[]' --output text | tr '\t' '\n')
  log "Removed task-definition revisions for ${prefix}-*"
fi

cat >&2 <<TEXT

Destroyed. What intentionally remains:
  - RDS final snapshot ${db_identifier}-final (if skip_final_snapshot=false):
      billed per GB-month; delete with
      aws rds delete-db-snapshot --db-snapshot-identifier ${db_identifier}-final
  - Secrets Manager secret ${prefix}/balldontlie-api-key: scheduled for deletion
      after its recovery window (restore-secret can undo it until then).
  - The Terraform state bucket from infra/terraform/bootstrap (cents per month).
  - Your AWS Budget is gone with the stack; keep an account-level budget if you
      continue using this AWS account.
Verify nothing billable is left in Billing > Bills and Cost Explorer tomorrow.
TEXT
