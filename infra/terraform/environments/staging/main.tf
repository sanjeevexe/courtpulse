# CourtPulse staging: composes the modules under ../../modules.
# Read docs/deployment/aws-staging.md before applying; every resource here
# is billed or free-tier limited, and nothing is created by CI.

data "aws_caller_identity" "current" {}

data "aws_region" "current" {}

# Digits only: Cognito rejects domain prefixes containing aws, amazon, or cognito.
resource "random_string" "cognito_domain_suffix" {
  length  = 8
  special = false
  upper   = false
  lower   = false
  numeric = true
}

locals {
  name_prefix = "${var.project}-${var.environment}"
  account_id  = data.aws_caller_identity.current.account_id
  region      = data.aws_region.current.region

  cluster_name          = local.name_prefix
  web_bucket_name       = "${local.name_prefix}-web-${local.account_id}"
  oneoff_log_group_name = "/ecs/${local.name_prefix}/one-off"

  # The CSP needs the Cognito hosted-UI host, and the Cognito client needs the
  # CloudFront domain. Building the Cognito host from region + a prefix string
  # (not from the domain resource) breaks that cycle: identity depends on
  # edge, edge depends only on this string.
  cognito_domain_prefix = "${local.name_prefix}-${random_string.cognito_domain_suffix.result}"
  cognito_domain_host   = "${local.cognito_domain_prefix}.auth.${local.region}.amazoncognito.com"
  cognito_idp_origin    = "https://cognito-idp.${local.region}.amazonaws.com"

  # Same directives as apps/web/nginx.conf, with the two Cognito origins the
  # browser contacts (discovery/JWKS and the token endpoint).
  content_security_policy = join("; ", [
    "default-src 'self'",
    "connect-src 'self' ${local.cognito_idp_origin} https://${local.cognito_domain_host}",
    "img-src 'self' data:",
    "style-src 'self'",
    "script-src 'self'",
    "font-src 'self'",
    "object-src 'none'",
    "base-uri 'self'",
    "form-action 'self'",
    "frame-ancestors 'none'",
  ])

  https_origin = var.alb_certificate_arn != null

  # Every long-running service and one-off task family.
  service_processes = ["api", "processor", "delivery", "reconciliation", "ingestor"]
  oneoff_processes  = ["migrate", "canary"]
  all_processes     = concat(local.service_processes, local.oneoff_processes)

  service_names   = { for process in local.service_processes : process => "${local.name_prefix}-${process}" }
  oneoff_families = { for process in local.oneoff_processes : process => "${local.name_prefix}-${process}" }

  # Deterministic role names let the deploy role scope iam:PassRole without
  # depending on the roles (see modules/cicd).
  task_role_names      = { for process in local.all_processes : process => "${local.name_prefix}-${process}-task" }
  execution_role_names = { for process in local.all_processes : process => "${local.name_prefix}-${process}-execution" }
  passable_role_arns = [
    for name in concat(values(local.task_role_names), values(local.execution_role_names)) :
    "arn:aws:iam::${local.account_id}:role/${name}"
  ]
}

module "network" {
  source = "../../modules/network"

  name_prefix       = local.name_prefix
  vpc_cidr          = var.vpc_cidr
  alb_listener_port = local.https_origin ? 443 : 80
}

module "messaging" {
  source = "../../modules/messaging"

  name_prefix = local.name_prefix
}

module "data" {
  source = "../../modules/data"

  name_prefix                  = local.name_prefix
  identifier                   = var.db_identifier
  private_subnet_ids           = module.network.private_subnet_ids
  security_group_ids           = [module.network.database_security_group_id]
  engine_version               = var.db_engine_version
  instance_class               = var.db_instance_class
  allocated_storage_gib        = var.db_allocated_storage_gib
  multi_az                     = var.db_multi_az
  backup_retention_days        = var.db_backup_retention_days
  performance_insights_enabled = var.db_performance_insights_enabled
  deletion_protection          = var.deletion_protection
  skip_final_snapshot          = var.skip_final_snapshot
  log_retention_days           = var.log_retention_days
}

module "edge" {
  source = "../../modules/edge"

  name_prefix              = local.name_prefix
  vpc_id                   = module.network.vpc_id
  public_subnet_ids        = module.network.public_subnet_ids
  alb_security_group_id    = module.network.alb_security_group_id
  web_bucket_name          = local.web_bucket_name
  web_bucket_force_destroy = var.web_bucket_force_destroy
  content_security_policy  = local.content_security_policy
  alb_certificate_arn      = var.alb_certificate_arn
  alb_origin_domain_name   = var.alb_origin_domain_name
  alb_idle_timeout_seconds = var.alb_idle_timeout_seconds
  enable_alb_access_logs   = var.enable_alb_access_logs
  cloudfront_price_class   = var.cloudfront_price_class
}

module "identity" {
  source = "../../modules/identity"

  name_prefix         = local.name_prefix
  domain_prefix       = local.cognito_domain_prefix
  web_origin          = "https://${module.edge.cloudfront_domain_name}"
  extra_callback_urls = var.extra_callback_urls
  extra_logout_urls   = var.extra_logout_urls
  allow_self_signup   = var.allow_self_signup
  deletion_protection = var.deletion_protection
}

module "cicd" {
  source = "../../modules/cicd"

  name_prefix                 = local.name_prefix
  project                     = var.project
  github_repository           = var.github_repository
  create_github_oidc_provider = var.create_github_oidc_provider
  ecr_force_delete            = var.ecr_force_delete
  cluster_name                = local.cluster_name
  service_names               = values(local.service_names)
  task_definition_families    = concat(values(local.service_names), values(local.oneoff_families))
  oneoff_task_families        = values(local.oneoff_families)
  passable_role_arns          = local.passable_role_arns
  web_bucket_name             = local.web_bucket_name
  oneoff_log_group_name       = local.oneoff_log_group_name
}

# The distribution ID is only known after creation, so this grant is attached
# here rather than inside modules/cicd (which must be creatable first).
data "aws_iam_policy_document" "deploy_cloudfront" {
  statement {
    sid       = "InvalidateSpaShell"
    actions   = ["cloudfront:CreateInvalidation", "cloudfront:GetInvalidation"]
    resources = [module.edge.cloudfront_distribution_arn]
  }
}

resource "aws_iam_role_policy" "deploy_cloudfront" {
  name   = "cloudfront-invalidation"
  role   = module.cicd.deploy_role_name
  policy = data.aws_iam_policy_document.deploy_cloudfront.json
}

# SES sends a verification link to this address; delivery fails until it is
# clicked. In the SES sandbox every recipient must be verified as well.
resource "aws_sesv2_email_identity" "alerts_from" {
  email_identity = var.email_from_address
}

# Created empty on purpose: Terraform never sees or stores the provider key.
# Set it with scripts/aws/set-provider-key.sh before scaling the ingestor up.
#trivy:ignore:AWS-0098 The AWS managed aws/secretsmanager key avoids a customer-managed KMS key charge.
resource "aws_secretsmanager_secret" "provider_key" {
  name                    = "${local.name_prefix}/balldontlie-api-key"
  description             = "BALLDONTLIE API key for the CourtPulse ingestor (value set out of band)"
  recovery_window_in_days = var.secret_recovery_window_days
}

module "observability" {
  source = "../../modules/observability"

  name_prefix                 = local.name_prefix
  alarm_email                 = var.alarm_email
  enable_topic_cmk            = var.enable_alarm_topic_cmk
  game_events_queue_name      = module.messaging.game_events_queue_name
  game_events_dlq_name        = module.messaging.game_events_dlq_name
  alert_deliveries_queue_name = module.messaging.alert_deliveries_queue_name
  alert_deliveries_dlq_name   = module.messaging.alert_deliveries_dlq_name
  alb_arn_suffix              = module.edge.alb_arn_suffix
  target_group_arn_suffix     = module.edge.api_target_group_arn_suffix
  db_instance_identifier      = module.data.instance_identifier
  cluster_name                = aws_ecs_cluster.this.name
  service_names               = local.service_names
  api_log_group_name          = module.api.log_group_name
  db_connections_threshold    = var.db_connections_alarm_threshold
}

module "cost" {
  source = "../../modules/cost"

  name_prefix             = local.name_prefix
  monthly_budget_usd      = var.monthly_budget_usd
  budget_email            = var.budget_email
  enable_nightly_shutdown = var.enable_nightly_shutdown
  schedule_timezone       = var.schedule_timezone
  shutdown_schedule       = var.shutdown_schedule
  database_start_schedule = var.database_start_schedule
  services_start_schedule = var.services_start_schedule
  cluster_name            = aws_ecs_cluster.this.name
  cluster_arn             = aws_ecs_cluster.this.arn
  db_instance_identifier  = module.data.instance_identifier
  db_instance_arn         = module.data.instance_arn

  services = {
    api            = { name = module.api.service_name, desired_count = var.api_desired_count }
    processor      = { name = module.processor.service_name, desired_count = var.processor_desired_count }
    delivery       = { name = module.delivery.service_name, desired_count = var.delivery_desired_count }
    reconciliation = { name = module.reconciliation.service_name, desired_count = var.reconciliation_desired_count }
    ingestor       = { name = module.ingestor.service_name, desired_count = var.ingestor_desired_count }
  }
}
