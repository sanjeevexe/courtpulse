output "cloudfront_url" {
  description = "Public HTTPS URL of the SPA and API."
  value       = "https://${module.edge.cloudfront_domain_name}"
}

output "cloudfront_distribution_id" {
  description = "CloudFront distribution ID."
  value       = module.edge.cloudfront_distribution_id
}

output "api_origin" {
  description = "ALB DNS name. Direct requests are refused (403); traffic must come through CloudFront."
  value       = module.edge.alb_dns_name
}

output "api_origin_protocol" {
  description = "Protocol on the CloudFront-to-ALB hop (http unless alb_certificate_arn is set)."
  value       = module.edge.origin_protocol
}

output "web_bucket_name" {
  description = "Private S3 bucket for the SPA build."
  value       = module.edge.web_bucket_name
}

output "cognito_user_pool_id" {
  description = "Cognito user pool ID (for admin-create-user and group membership)."
  value       = module.identity.user_pool_id
}

output "cognito_issuer_uri" {
  description = "OIDC issuer URI configured in the API."
  value       = module.identity.issuer_uri
}

output "cognito_client_id" {
  description = "Public PKCE app client ID."
  value       = module.identity.client_id
}

output "cognito_domain" {
  description = "Cognito hosted UI domain."
  value       = module.identity.domain
}

output "cognito_hosted_ui_url" {
  description = "Hosted UI sign-in page (renders the login form; real sign-in starts from the SPA so PKCE state exists)."
  value       = module.identity.hosted_ui_url
}

output "cognito_operations_group" {
  description = "Group granting the operations authority."
  value       = module.identity.operations_group_name
}

output "content_security_policy" {
  description = "CSP served by CloudFront."
  value       = local.content_security_policy
}

output "ecr_api_repository_url" {
  description = "ECR repository URL for courtpulse-api."
  value       = module.cicd.api_repository_url
}

output "ecr_worker_repository_url" {
  description = "ECR repository URL for courtpulse-worker."
  value       = module.cicd.worker_repository_url
}

output "deploy_role_arn" {
  description = "IAM role assumed by GitHub Actions through OIDC."
  value       = module.cicd.deploy_role_arn
}

output "ecs_cluster_name" {
  description = "ECS cluster name."
  value       = aws_ecs_cluster.this.name
}

output "ecs_service_names" {
  description = "ECS service names by process."
  value = {
    api            = module.api.service_name
    processor      = module.processor.service_name
    delivery       = module.delivery.service_name
    reconciliation = module.reconciliation.service_name
    ingestor       = module.ingestor.service_name
  }
}

output "migrate_task_family" {
  description = "Task definition family for the one-off --migrate task."
  value       = module.migrate.task_definition_family
}

output "canary_task_family" {
  description = "Task definition family for the one-off --canary task."
  value       = module.canary.task_definition_family
}

output "oneoff_log_group_name" {
  description = "Log group for migrate and canary tasks."
  value       = aws_cloudwatch_log_group.oneoff.name
}

output "public_subnet_ids" {
  description = "Public subnets (tasks run here with public IPs; use for run-task)."
  value       = module.network.public_subnet_ids
}

output "private_subnet_ids" {
  description = "Private subnets (RDS only)."
  value       = module.network.private_subnet_ids
}

output "worker_security_group_id" {
  description = "Security group for workers and one-off tasks (use for run-task)."
  value       = module.network.worker_security_group_id
}

output "api_security_group_id" {
  description = "Security group for API tasks."
  value       = module.network.api_security_group_id
}

output "database_endpoint" {
  description = "RDS address (private)."
  value       = module.data.address
}

output "database_identifier" {
  description = "RDS instance identifier."
  value       = module.data.instance_identifier
}

output "database_parameter_group_name" {
  description = "RDS parameter group (reused by point-in-time restores)."
  value       = module.data.parameter_group_name
}

output "database_subnet_group_name" {
  description = "RDS subnet group (reused by point-in-time restores)."
  value       = module.data.subnet_group_name
}

output "database_security_group_id" {
  description = "RDS security group (reused by point-in-time restores)."
  value       = module.network.database_security_group_id
}

output "database_master_secret_arn" {
  description = "ARN of the RDS-managed master credential secret (value never in state)."
  value       = module.data.master_user_secret_arn
}

output "provider_key_secret_arn" {
  description = "ARN of the empty BALLDONTLIE key secret; set it with scripts/aws/set-provider-key.sh."
  value       = aws_secretsmanager_secret.provider_key.arn
}

output "ses_from_identity" {
  description = "SES identity that must be verified before alert email can be sent."
  value       = aws_sesv2_email_identity.alerts_from.email_identity
}

output "alarm_topic_arn" {
  description = "SNS topic for CloudWatch alarms."
  value       = module.observability.alarm_topic_arn
}

output "dashboard_name" {
  description = "CloudWatch dashboard name."
  value       = module.observability.dashboard_name
}

output "github_actions_variables" {
  description = "Non-secret GitHub Actions variables for the deploy and rollback workflows (scripts/aws/github-variables.sh prints or sets them)."
  value = {
    AWS_REGION                 = local.region
    AWS_DEPLOY_ROLE_ARN        = module.cicd.deploy_role_arn
    ECR_API_REPOSITORY         = module.cicd.api_repository_url
    ECR_WORKER_REPOSITORY      = module.cicd.worker_repository_url
    ECS_CLUSTER                = aws_ecs_cluster.this.name
    ECS_SERVICES               = join(" ", [module.api.service_name, module.processor.service_name, module.delivery.service_name, module.reconciliation.service_name, module.ingestor.service_name])
    MIGRATE_TASK_FAMILY        = module.migrate.task_definition_family
    CANARY_TASK_FAMILY         = module.canary.task_definition_family
    ONEOFF_LOG_GROUP           = aws_cloudwatch_log_group.oneoff.name
    TASK_SUBNETS               = join(",", module.network.public_subnet_ids)
    TASK_SECURITY_GROUP        = module.network.worker_security_group_id
    WEB_BUCKET                 = module.edge.web_bucket_name
    CLOUDFRONT_DISTRIBUTION_ID = module.edge.cloudfront_distribution_id
    CLOUDFRONT_URL             = "https://${module.edge.cloudfront_domain_name}"
  }
}
