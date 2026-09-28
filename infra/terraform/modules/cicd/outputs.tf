output "api_repository_url" {
  description = "ECR repository URL for courtpulse-api."
  value       = aws_ecr_repository.this["api"].repository_url
}

output "api_repository_arn" {
  description = "ECR repository ARN for courtpulse-api."
  value       = aws_ecr_repository.this["api"].arn
}

output "worker_repository_url" {
  description = "ECR repository URL for courtpulse-worker."
  value       = aws_ecr_repository.this["worker"].repository_url
}

output "worker_repository_arn" {
  description = "ECR repository ARN for courtpulse-worker."
  value       = aws_ecr_repository.this["worker"].arn
}

output "deploy_role_arn" {
  description = "Role GitHub Actions assumes (AWS_DEPLOY_ROLE_ARN)."
  value       = aws_iam_role.deploy.arn
}

output "deploy_role_name" {
  description = "Deploy role name (the environment attaches the CloudFront invalidation grant to it)."
  value       = aws_iam_role.deploy.name
}

output "github_oidc_provider_arn" {
  description = "ARN of the GitHub Actions OIDC provider in this account."
  value       = local.github_oidc_provider_arn
}
