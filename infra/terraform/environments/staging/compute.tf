# ECS cluster, per-process least-privilege task policies, and the seven
# process definitions from the runtime contract:
#
#   service         image               command                    SQS permissions
#   api             courtpulse-api      (image default)            none
#   processor       courtpulse-worker   --processor-daemon         game-events send/receive/delete/visibility
#   delivery        courtpulse-worker   --delivery-daemon          alert-deliveries send/receive/delete/visibility
#   reconciliation  courtpulse-worker   --reconciliation-daemon    game-events + DLQ attributes (depth observation)
#   ingestor        courtpulse-worker   --ingest-daemon            none beyond GetQueueUrl
#   migrate (task)  courtpulse-worker   --migrate                  none beyond GetQueueUrl
#   canary  (task)  courtpulse-worker   --canary                   none beyond GetQueueUrl
#
# The worker image resolves all four queue URLs at startup in every mode, so
# every worker-image role gets metadata-only sqs:GetQueueUrl on all four.

#trivy:ignore:AWS-0034 Container Insights is billed per metric; enable_container_insights turns it on.
resource "aws_ecs_cluster" "this" {
  name = local.cluster_name

  setting {
    name  = "containerInsights"
    value = var.enable_container_insights ? "enabled" : "disabled"
  }

  lifecycle {
    # The one-time bootstrap (`apply -target=module.cicd`) never reaches the
    # cluster, so this only stops a full apply that has no image to run.
    precondition {
      condition     = var.image_tag != null
      error_message = "Set image_tag to the full git SHA of pushed courtpulse-api and courtpulse-worker images before a full apply (docs/deployment/aws-staging.md, step 4)."
    }
  }
}

resource "aws_ecs_cluster_capacity_providers" "this" {
  cluster_name       = aws_ecs_cluster.this.name
  capacity_providers = ["FARGATE", "FARGATE_SPOT"]

  default_capacity_provider_strategy {
    capacity_provider = "FARGATE"
    weight            = 1
  }
}

#trivy:ignore:AWS-0017 CloudWatch Logs default encryption is used; a customer-managed KMS key would add a monthly charge.
resource "aws_cloudwatch_log_group" "oneoff" {
  name              = local.oneoff_log_group_name
  retention_in_days = var.log_retention_days
}

locals {
  # Referencing the capacity-provider association orders every service after
  # it (a FARGATE_SPOT strategy fails on a cluster without that provider).
  cluster_arn = aws_ecs_cluster_capacity_providers.this.cluster_name == aws_ecs_cluster.this.name ? aws_ecs_cluster.this.arn : null

  api_image    = var.image_tag == null ? null : "${module.cicd.api_repository_url}:${var.image_tag}"
  worker_image = var.image_tag == null ? null : "${module.cicd.worker_repository_url}:${var.image_tag}"

  db_secret_arn = module.data.master_user_secret_arn
  db_secrets = {
    COURTPULSE_DB_USERNAME = "${local.db_secret_arn}:username::"
    COURTPULSE_DB_PASSWORD = "${local.db_secret_arn}:password::"
  }

  base_environment = {
    AWS_REGION        = local.region
    COURTPULSE_DB_URL = module.data.jdbc_url
  }

  worker_environment = merge(local.base_environment, {
    # "aws" = AWS SDK default endpoint and credential chain (task role); LocalStack is local only.
    COURTPULSE_SQS_ENDPOINT                    = "aws"
    COURTPULSE_GAME_EVENTS_QUEUE               = module.messaging.game_events_queue_name
    COURTPULSE_GAME_EVENTS_DLQ                 = module.messaging.game_events_dlq_name
    COURTPULSE_ALERT_DELIVERIES_QUEUE          = module.messaging.alert_deliveries_queue_name
    COURTPULSE_ALERT_DELIVERIES_DLQ            = module.messaging.alert_deliveries_dlq_name
    SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE = tostring(var.worker_db_pool_size)
    # Long polls return as soon as a message arrives; 20 s only cuts idle receive requests.
    COURTPULSE_CONSUMER_LONG_POLL = "20s"
  })

  web_origin = "https://${module.edge.cloudfront_domain_name}"

  api_environment = merge(local.base_environment, {
    COURTPULSE_API_DB_POOL_SIZE          = tostring(var.api_db_pool_size)
    COURTPULSE_FORWARD_HEADERS_STRATEGY  = "framework"
    COURTPULSE_AUTH_ENABLED              = "true"
    COURTPULSE_AUTH_ISSUER_URI           = module.identity.issuer_uri
    COURTPULSE_AUTH_AUDIENCE             = module.identity.client_id
    COURTPULSE_AUTH_AUDIENCE_CLAIM       = "client_id"
    COURTPULSE_AUTH_REQUIRED_TOKEN_USE   = "access"
    COURTPULSE_AUTH_CLIENT_ID            = module.identity.client_id
    COURTPULSE_AUTH_BROWSER_SCOPE        = "openid email profile"
    COURTPULSE_AUTH_AUTHORITIES_CLAIM    = "cognito:groups"
    COURTPULSE_AUTH_AUTHORITY_PREFIX     = "ROLE_"
    COURTPULSE_AUTH_OPERATIONS_AUTHORITY = "ROLE_${module.identity.operations_group_name}"
    COURTPULSE_AUTH_END_SESSION_ENDPOINT = module.identity.end_session_endpoint
    # Browser origin for the WebSocket handshake check. Behind CloudFront and
    # the ALB the API sees Host=<origin> and X-Forwarded-Proto=http, so
    # Spring's default same-origin check cannot match https://<cloudfront>.
    COURTPULSE_REALTIME_ALLOWED_ORIGINS = local.web_origin
  })

  # Worker image commands from the runtime contract (ENTRYPOINT is java -jar /app/courtpulse-worker.jar).
  process_commands = {
    processor      = ["--processor-daemon"]
    delivery       = ["--delivery-daemon"]
    reconciliation = ["--reconciliation-daemon"]
    ingestor       = ["--ingest-daemon"]
    migrate        = ["--migrate"]
    canary         = ["--canary"]
  }

  delivery_environment = merge(local.worker_environment, {
    COURTPULSE_EMAIL_PROVIDER  = "ses"
    COURTPULSE_EMAIL_FROM      = var.email_from_address
    COURTPULSE_PUBLIC_BASE_URL = local.web_origin
  })

  ingestor_environment = merge(local.worker_environment, {
    COURTPULSE_PROVIDER                     = "balldontlie"
    COURTPULSE_PROVIDER_BASE_URL            = var.provider_base_url
    COURTPULSE_PROVIDER_REQUESTS_PER_MINUTE = tostring(var.provider_requests_per_minute)
    COURTPULSE_INGEST_POLL_INTERVAL         = var.ingest_poll_interval
  })

  worker_health_check = {
    for process in ["processor", "delivery", "reconciliation", "ingestor"] : process => {
      command      = ["CMD-SHELL", "find /tmp/courtpulse-${process}-worker.heartbeat -mmin -1 | grep -q heartbeat"]
      interval     = 30
      timeout      = 5
      retries      = 3
      start_period = 60
    }
  }

  tracing = {
    enabled           = var.enable_tracing
    collector_image   = var.otel_collector_image
    sampling_ratio    = var.trace_sampling_ratio
    service_namespace = var.project
    environment_name  = var.environment
  }

  worker_service_defaults = {
    subnet_ids         = module.network.public_subnet_ids
    security_group_ids = [module.network.worker_security_group_id]
  }
}

# ---------------------------------------------------------------------------
# Task role policies
# ---------------------------------------------------------------------------

data "aws_iam_policy_document" "resolve_queue_urls" {
  statement {
    sid       = "ResolveQueueUrls"
    actions   = ["sqs:GetQueueUrl"]
    resources = module.messaging.all_queue_arns
  }
}

data "aws_iam_policy_document" "processor" {
  source_policy_documents = [data.aws_iam_policy_document.resolve_queue_urls.json]

  statement {
    sid = "ConsumeAndPublishGameEvents"
    actions = [
      "sqs:SendMessage",
      "sqs:ReceiveMessage",
      "sqs:DeleteMessage",
      "sqs:ChangeMessageVisibility",
      "sqs:GetQueueAttributes",
    ]
    resources = [module.messaging.game_events_queue_arn]
  }

  statement {
    sid       = "ObserveGameEventsDlq"
    actions   = ["sqs:GetQueueAttributes"]
    resources = [module.messaging.game_events_dlq_arn]
  }
}

data "aws_iam_policy_document" "delivery" {
  source_policy_documents = [data.aws_iam_policy_document.resolve_queue_urls.json]

  statement {
    sid = "ConsumeAndPublishAlertDeliveries"
    actions = [
      "sqs:SendMessage",
      "sqs:ReceiveMessage",
      "sqs:DeleteMessage",
      "sqs:ChangeMessageVisibility",
      "sqs:GetQueueAttributes",
    ]
    resources = [module.messaging.alert_deliveries_queue_arn]
  }

  statement {
    sid       = "ObserveAlertDeliveriesDlq"
    actions   = ["sqs:GetQueueAttributes"]
    resources = [module.messaging.alert_deliveries_dlq_arn]
  }

  # In the SES sandbox, authorization is also evaluated against the verified
  # recipient identities, so the resource is every identity in this account;
  # the sender is pinned by ses:FromAddress.
  statement {
    sid       = "SendAlertEmailFromOneAddress"
    actions   = ["ses:SendEmail"]
    resources = ["arn:aws:ses:${local.region}:${local.account_id}:identity/*"]

    condition {
      test     = "StringEquals"
      variable = "ses:FromAddress"
      values   = [var.email_from_address]
    }
  }
}

data "aws_iam_policy_document" "reconciliation" {
  source_policy_documents = [data.aws_iam_policy_document.resolve_queue_urls.json]

  statement {
    sid       = "ObserveGameEventsDepth"
    actions   = ["sqs:GetQueueAttributes"]
    resources = [module.messaging.game_events_queue_arn, module.messaging.game_events_dlq_arn]
  }
}

# ---------------------------------------------------------------------------
# Services
# ---------------------------------------------------------------------------

module "api" {
  source = "../../modules/ecs-service"

  name                = "api"
  name_prefix         = local.name_prefix
  cluster_arn         = local.cluster_arn
  image               = local.api_image
  ecr_repository_arn  = module.cicd.api_repository_arn
  task_role_name      = local.task_role_names["api"]
  execution_role_name = local.execution_role_names["api"]
  cpu                 = var.api_cpu
  memory              = var.api_memory
  container_port      = 8080
  desired_count       = var.api_desired_count
  capacity_provider   = "FARGATE"
  environment         = local.api_environment
  secrets             = local.db_secrets

  execution_secret_arns = [local.db_secret_arn]

  health_check = {
    command      = ["CMD-SHELL", "wget -q --spider http://127.0.0.1:8080/actuator/health/liveness || exit 1"]
    interval     = 15
    timeout      = 5
    retries      = 3
    start_period = 90
  }

  load_balancer = {
    target_group_arn                  = module.edge.api_target_group_arn
    health_check_grace_period_seconds = 120
  }

  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200

  subnet_ids               = module.network.public_subnet_ids
  security_group_ids       = [module.network.api_security_group_id]
  assign_public_ip         = true
  log_retention_days       = var.log_retention_days
  enable_execute_command   = var.enable_execute_command
  readonly_root_filesystem = var.readonly_root_filesystem
  tracing                  = local.tracing
}

module "processor" {
  source = "../../modules/ecs-service"

  name                  = "processor"
  name_prefix           = local.name_prefix
  cluster_arn           = local.cluster_arn
  image                 = local.worker_image
  command               = local.process_commands["processor"]
  ecr_repository_arn    = module.cicd.worker_repository_arn
  task_role_name        = local.task_role_names["processor"]
  execution_role_name   = local.execution_role_names["processor"]
  task_role_policies    = { sqs = data.aws_iam_policy_document.processor.json }
  cpu                   = var.worker_cpu
  memory                = var.worker_memory
  desired_count         = var.processor_desired_count
  capacity_provider     = var.worker_capacity_provider
  environment           = local.worker_environment
  secrets               = local.db_secrets
  execution_secret_arns = [local.db_secret_arn]
  health_check          = local.worker_health_check["processor"]

  subnet_ids               = local.worker_service_defaults.subnet_ids
  security_group_ids       = local.worker_service_defaults.security_group_ids
  log_retention_days       = var.log_retention_days
  enable_execute_command   = var.enable_execute_command
  readonly_root_filesystem = var.readonly_root_filesystem
  tracing                  = local.tracing
}

module "delivery" {
  source = "../../modules/ecs-service"

  name                  = "delivery"
  name_prefix           = local.name_prefix
  cluster_arn           = local.cluster_arn
  image                 = local.worker_image
  command               = local.process_commands["delivery"]
  ecr_repository_arn    = module.cicd.worker_repository_arn
  task_role_name        = local.task_role_names["delivery"]
  execution_role_name   = local.execution_role_names["delivery"]
  task_role_policies    = { sqs-and-ses = data.aws_iam_policy_document.delivery.json }
  cpu                   = var.worker_cpu
  memory                = var.worker_memory
  desired_count         = var.delivery_desired_count
  capacity_provider     = var.worker_capacity_provider
  secrets               = local.db_secrets
  execution_secret_arns = [local.db_secret_arn]
  health_check          = local.worker_health_check["delivery"]

  environment = local.delivery_environment

  subnet_ids               = local.worker_service_defaults.subnet_ids
  security_group_ids       = local.worker_service_defaults.security_group_ids
  log_retention_days       = var.log_retention_days
  enable_execute_command   = var.enable_execute_command
  readonly_root_filesystem = var.readonly_root_filesystem
  tracing                  = local.tracing
}

module "reconciliation" {
  source = "../../modules/ecs-service"

  name                  = "reconciliation"
  name_prefix           = local.name_prefix
  cluster_arn           = local.cluster_arn
  image                 = local.worker_image
  command               = local.process_commands["reconciliation"]
  ecr_repository_arn    = module.cicd.worker_repository_arn
  task_role_name        = local.task_role_names["reconciliation"]
  execution_role_name   = local.execution_role_names["reconciliation"]
  task_role_policies    = { sqs = data.aws_iam_policy_document.reconciliation.json }
  cpu                   = var.worker_cpu
  memory                = var.worker_memory
  desired_count         = var.reconciliation_desired_count
  capacity_provider     = var.worker_capacity_provider
  environment           = local.worker_environment
  secrets               = local.db_secrets
  execution_secret_arns = [local.db_secret_arn]
  health_check          = local.worker_health_check["reconciliation"]

  subnet_ids               = local.worker_service_defaults.subnet_ids
  security_group_ids       = local.worker_service_defaults.security_group_ids
  log_retention_days       = var.log_retention_days
  enable_execute_command   = var.enable_execute_command
  readonly_root_filesystem = var.readonly_root_filesystem
  tracing                  = local.tracing
}

module "ingestor" {
  source = "../../modules/ecs-service"

  name                = "ingestor"
  name_prefix         = local.name_prefix
  cluster_arn         = local.cluster_arn
  image               = local.worker_image
  command             = local.process_commands["ingestor"]
  ecr_repository_arn  = module.cicd.worker_repository_arn
  task_role_name      = local.task_role_names["ingestor"]
  execution_role_name = local.execution_role_names["ingestor"]
  task_role_policies  = { sqs = data.aws_iam_policy_document.resolve_queue_urls.json }
  cpu                 = var.worker_cpu
  memory              = var.worker_memory
  desired_count       = var.ingestor_desired_count
  capacity_provider   = var.worker_capacity_provider
  health_check        = local.worker_health_check["ingestor"]

  environment = local.ingestor_environment

  secrets = merge(local.db_secrets, {
    COURTPULSE_BALLDONTLIE_API_KEY = aws_secretsmanager_secret.provider_key.arn
  })
  execution_secret_arns = [local.db_secret_arn, aws_secretsmanager_secret.provider_key.arn]

  subnet_ids               = local.worker_service_defaults.subnet_ids
  security_group_ids       = local.worker_service_defaults.security_group_ids
  log_retention_days       = var.log_retention_days
  enable_execute_command   = var.enable_execute_command
  readonly_root_filesystem = var.readonly_root_filesystem
  tracing                  = local.tracing
}

# ---------------------------------------------------------------------------
# One-off task families (run by CI with ecs run-task; no service)
# ---------------------------------------------------------------------------

module "migrate" {
  source = "../../modules/ecs-service"

  name                  = "migrate"
  name_prefix           = local.name_prefix
  create_service        = false
  cluster_arn           = local.cluster_arn
  image                 = local.worker_image
  command               = local.process_commands["migrate"]
  ecr_repository_arn    = module.cicd.worker_repository_arn
  task_role_name        = local.task_role_names["migrate"]
  execution_role_name   = local.execution_role_names["migrate"]
  task_role_policies    = { sqs = data.aws_iam_policy_document.resolve_queue_urls.json }
  cpu                   = var.worker_cpu
  memory                = var.worker_memory
  environment           = local.worker_environment
  secrets               = local.db_secrets
  execution_secret_arns = [local.db_secret_arn]
  log_group_name        = aws_cloudwatch_log_group.oneoff.name

  subnet_ids               = local.worker_service_defaults.subnet_ids
  security_group_ids       = local.worker_service_defaults.security_group_ids
  readonly_root_filesystem = var.readonly_root_filesystem
}

module "canary" {
  source = "../../modules/ecs-service"

  name                  = "canary"
  name_prefix           = local.name_prefix
  create_service        = false
  cluster_arn           = local.cluster_arn
  image                 = local.worker_image
  command               = local.process_commands["canary"]
  ecr_repository_arn    = module.cicd.worker_repository_arn
  task_role_name        = local.task_role_names["canary"]
  execution_role_name   = local.execution_role_names["canary"]
  task_role_policies    = { sqs = data.aws_iam_policy_document.resolve_queue_urls.json }
  cpu                   = var.worker_cpu
  memory                = var.worker_memory
  environment           = local.worker_environment
  secrets               = local.db_secrets
  execution_secret_arns = [local.db_secret_arn]
  log_group_name        = aws_cloudwatch_log_group.oneoff.name

  subnet_ids               = local.worker_service_defaults.subnet_ids
  security_group_ids       = local.worker_service_defaults.security_group_ids
  readonly_root_filesystem = var.readonly_root_filesystem
}
