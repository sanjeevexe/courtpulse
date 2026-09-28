#!/usr/bin/env bash
# Build and push courtpulse-api and courtpulse-worker for one git SHA.
#
#   ECR_API_REPOSITORY=<account>.dkr.ecr.<region>.amazonaws.com/courtpulse-api \
#   ECR_WORKER_REPOSITORY=<account>.dkr.ecr.<region>.amazonaws.com/courtpulse-worker \
#   AWS_REGION=us-east-1 scripts/aws/build-push-images.sh "$(git rev-parse HEAD)"
#
# Tags are immutable in ECR, so an existing tag is skipped instead of rebuilt
# (reruns are safe). Images carry BuildKit provenance (mode=max) and an SPDX
# SBOM as attestations. Run from a clean checkout of that SHA. On Apple Silicon
# the linux/amd64 build runs under emulation and is slower.
set -Eeuo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/aws/lib.sh
source "${script_dir}/lib.sh"

tag="${1:-}"
require_git_sha "$tag"
require_cmd aws docker git
require_env AWS_REGION ECR_API_REPOSITORY ECR_WORKER_REPOSITORY

repo_root="$(git -C "$script_dir" rev-parse --show-toplevel)"
platform="${IMAGE_PLATFORM:-linux/amd64}"

if [[ "$(git -C "$repo_root" rev-parse HEAD)" != "$tag" ]]; then
  die "Checked-out HEAD is not ${tag}; check out that commit so the image matches its tag"
fi
if [[ -n "$(git -C "$repo_root" status --porcelain --untracked-files=no)" ]]; then
  die "Working tree has uncommitted changes; images must be built from the tagged commit only"
fi

registry="${ECR_API_REPOSITORY%%/*}"
if [[ "${SKIP_ECR_LOGIN:-false}" != "true" ]]; then
  log "Logging Docker in to ${registry}"
  aws ecr get-login-password --region "$AWS_REGION" \
    | docker login --username AWS --password-stdin "$registry" >/dev/null
fi

build_and_push() {
  local repository_url="$1" dockerfile="$2" repository_name
  repository_name="${repository_url#*/}"

  if aws ecr describe-images --repository-name "$repository_name" --image-ids "imageTag=${tag}" \
    --query 'imageDetails[0].imageDigest' --output text >/dev/null 2>&1; then
    log "${repository_name}:${tag:0:12} already exists; skipping (tags are immutable)"
    return 0
  fi

  log "Building ${repository_name}:${tag:0:12} from ${dockerfile} for ${platform}"
  docker buildx build \
    --platform "$platform" \
    --file "${repo_root}/${dockerfile}" \
    --tag "${repository_url}:${tag}" \
    --label "org.opencontainers.image.revision=${tag}" \
    --label "org.opencontainers.image.source=${IMAGE_SOURCE_URL:-https://github.com/${GITHUB_REPOSITORY:-unknown}}" \
    --provenance=mode=max \
    --sbom=true \
    --push \
    "$repo_root"
}

build_and_push "$ECR_API_REPOSITORY" "apps/api/Dockerfile"
build_and_push "$ECR_WORKER_REPOSITORY" "apps/queue-replay-cli/Dockerfile"
log "Images for ${tag:0:12} are in ECR"
