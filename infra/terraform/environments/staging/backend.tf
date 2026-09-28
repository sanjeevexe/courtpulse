# Partial configuration: bucket, key, and region come from an ignored
# backend.hcl written by scripts/aws/bootstrap-state.sh:
#   terraform init -backend-config=backend.hcl
# The lock file lives beside the state object (no DynamoDB table).
terraform {
  backend "s3" {
    encrypt      = true
    use_lockfile = true
  }
}
