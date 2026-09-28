output "state_bucket_name" {
  description = "Name of the versioned, encrypted Terraform state bucket."
  value       = aws_s3_bucket.state.bucket
}

output "state_bucket_region" {
  description = "Region of the Terraform state bucket (use it in backend.hcl)."
  value       = data.aws_region.current.region
}

output "staging_backend_config" {
  description = "Suggested partial backend configuration for environments/staging/backend.hcl."
  value = {
    bucket       = aws_s3_bucket.state.bucket
    key          = "${var.project}/staging/terraform.tfstate"
    region       = data.aws_region.current.region
    encrypt      = true
    use_lockfile = true
  }
}
