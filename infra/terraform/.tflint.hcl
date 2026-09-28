# Shared TFLint configuration for every root and module under infra/terraform.
# Run from the repository root (see docs/deployment/aws-staging.md):
#   tflint --init --config="$PWD/infra/terraform/.tflint.hcl"
#   tflint --recursive --config="$PWD/infra/terraform/.tflint.hcl" --chdir=infra/terraform
# Deep checking is disabled on purpose: it would call AWS APIs with credentials.
config {
  call_module_type = "local"
}

plugin "terraform" {
  enabled = true
  preset  = "all"
}

plugin "aws" {
  enabled    = true
  version    = "0.49.0"
  source     = "github.com/terraform-linters/tflint-ruleset-aws"
  deep_check = false
}
