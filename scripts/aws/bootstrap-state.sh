#!/usr/bin/env bash
# One-time setup of the Terraform remote-state bucket, then the partial
# backend configuration for infra/terraform/environments/staging.
#
#   scripts/aws/bootstrap-state.sh
#
# Uses your current AWS CLI credentials (shown before anything happens) and
# Terraform's own interactive plan/approve prompt. The bucket costs a few
# cents per month. Its local state stays in infra/terraform/bootstrap
# (ignored by git): keep that directory, or re-import the bucket if lost.
set -Eeuo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/aws/lib.sh
source "${script_dir}/lib.sh"

require_cmd aws terraform git jq
repo_root="$(git -C "$script_dir" rev-parse --show-toplevel)"
bootstrap_dir="${repo_root}/infra/terraform/bootstrap"
staging_dir="${repo_root}/infra/terraform/environments/staging"
backend_file="${staging_dir}/backend.hcl"

identity="$(aws sts get-caller-identity --query '[Account, Arn]' --output text)"
log "AWS identity: ${identity}"
if [[ ! -f "${bootstrap_dir}/terraform.tfvars" ]]; then
  die "Create ${bootstrap_dir}/terraform.tfvars from terraform.tfvars.example first (owner is required)"
fi

read -r -p "Create or update the Terraform state bucket in this account? [y/N] " answer
[[ "$answer" == "y" || "$answer" == "Y" ]] || die "Cancelled"

terraform -chdir="$bootstrap_dir" init -input=false
terraform -chdir="$bootstrap_dir" apply

bucket="$(terraform -chdir="$bootstrap_dir" output -raw state_bucket_name)"
region="$(terraform -chdir="$bootstrap_dir" output -raw state_bucket_region)"
state_key="$(terraform -chdir="$bootstrap_dir" output -json staging_backend_config | jq -r .key)"

if [[ -f "$backend_file" ]]; then
  read -r -p "${backend_file} exists. Overwrite? [y/N] " answer
  [[ "$answer" == "y" || "$answer" == "Y" ]] || { log "Kept existing ${backend_file}"; exit 0; }
fi

umask 077
cat >"$backend_file" <<HCL
# Written by scripts/aws/bootstrap-state.sh (ignored by git).
bucket = "${bucket}"
key    = "${state_key}"
region = "${region}"
HCL

log "Wrote ${backend_file}"
log "Next: terraform -chdir=infra/terraform/environments/staging init -backend-config=backend.hcl"
