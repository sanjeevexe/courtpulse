#!/usr/bin/env bash
# Print (default) or set the GitHub Actions repository variables that the
# deploy and rollback workflows read, from Terraform outputs. Every value is a
# non-secret identifier; no GitHub secret is needed because AWS access uses OIDC.
#
#   scripts/aws/github-variables.sh            # print gh commands to review
#   scripts/aws/github-variables.sh --apply    # run them with the gh CLI
#
# Repository-level variables are used on purpose: the workflows' job-level
# `if: vars.AWS_DEPLOY_ROLE_ARN != ''` guard is evaluated before a job enters
# its environment, so it cannot see environment-scoped variables.
set -Eeuo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/aws/lib.sh
source "${script_dir}/lib.sh"

require_cmd terraform jq git
repo_root="$(git -C "$script_dir" rev-parse --show-toplevel)"
tf_dir="${TF_DIR:-${repo_root}/infra/terraform/environments/staging}"
mode="${1:-print}"

variables_json="$(terraform -chdir="$tf_dir" output -json github_actions_variables)"

while IFS=$'\t' read -r name value; do
  if [[ "$mode" == "--apply" ]]; then
    require_cmd gh
    gh variable set "$name" --body "$value" >/dev/null
    log "Set ${name}"
  else
    printf 'gh variable set %s --body %q\n' "$name" "$value"
  fi
done < <(jq -r 'to_entries[] | [.key, .value] | @tsv' <<<"$variables_json")
