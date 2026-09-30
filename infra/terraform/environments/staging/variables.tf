# ---------------------------------------------------------------------------
# Identity and tagging
# ---------------------------------------------------------------------------

variable "region" {
  description = "AWS region for the stack."
  type        = string
  default     = "us-east-1"
}

variable "project" {
  description = "Short lowercase project name used in names and the Application tag."
  type        = string
  default     = "courtpulse"

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{1,15}$", var.project))
    error_message = "project must be 2-16 lowercase letters, digits, or hyphens and start with a letter."
  }
}

variable "environment" {
  description = "Environment name used in names and the Environment tag."
  type        = string
  default     = "staging"

  validation {
    condition     = can(regex("^[a-z][a-z0-9]{1,10}$", var.environment))
    error_message = "environment must be 2-11 lowercase letters or digits."
  }
}

variable "owner" {
  description = "Owner tag value (the person or team responsible)."
  type        = string

  validation {
    condition     = length(trimspace(var.owner)) > 0
    error_message = "owner must not be blank."
  }
}

variable "cost_center" {
  description = "CostCenter tag value."
  type        = string
  default     = "portfolio"
}

# ---------------------------------------------------------------------------
# Delivery
# ---------------------------------------------------------------------------

variable "image_tag" {
  description = "Git SHA of pushed courtpulse-api and courtpulse-worker images used when Terraform registers task-definition revisions. Leave null only for the one-time `-target=module.cicd` bootstrap. CI deploys newer tags without changing this."
  type        = string
  default     = null

  validation {
    condition     = var.image_tag == null || can(regex("^[0-9a-f]{40}$", var.image_tag))
    error_message = "image_tag must be a full 40-character lowercase git SHA."
  }
}

variable "github_repository" {
  description = "GitHub repository (owner/name) allowed to deploy through OIDC, with GitHub's exact letter case (the IAM sub-claim match is case-sensitive)."
  type        = string
}

variable "create_github_oidc_provider" {
  description = "Create the account-wide GitHub OIDC provider. Set false if it already exists."
  type        = bool
  default     = true
}

variable "ecr_force_delete" {
  description = "Let terraform destroy delete ECR repositories that still contain images."
  type        = bool
  default     = false
}

# ---------------------------------------------------------------------------
# Network and edge
# ---------------------------------------------------------------------------

variable "vpc_cidr" {
  description = "VPC CIDR block."
  type        = string
  default     = "10.40.0.0/16"
}

variable "alb_certificate_arn" {
  description = "Optional ACM certificate for an HTTPS ALB origin (requires a domain you control). Null keeps the HTTP origin."
  type        = string
  default     = null
}

variable "alb_origin_domain_name" {
  description = "Domain covered by alb_certificate_arn that points at the ALB. Required with a certificate."
  type        = string
  default     = null
}

variable "alb_idle_timeout_seconds" {
  description = "ALB idle timeout in seconds (>= 75 for WebSockets)."
  type        = number
  default     = 120
}

variable "enable_alb_access_logs" {
  description = "Write ALB access logs to S3 (adds storage and request charges)."
  type        = bool
  default     = false
}

variable "cloudfront_price_class" {
  description = "CloudFront price class."
  type        = string
  default     = "PriceClass_100"
}

variable "web_bucket_force_destroy" {
  description = "Allow destroy to empty the SPA bucket (its contents are reproducible build output)."
  type        = bool
  default     = true
}

# ---------------------------------------------------------------------------
# Database
# ---------------------------------------------------------------------------

variable "db_engine_version" {
  description = "PostgreSQL version. 17.11 was the newest RDS 17.x minor on 2026-09-28; \"17\" tracks the RDS default minor without drift."
  type        = string
  default     = "17.11"
}

variable "db_identifier" {
  description = "RDS identifier override. Leave null (<project>-<environment>-postgres) except when adopting a point-in-time restore (docs/runbooks/database-restore.md)."
  type        = string
  default     = null
}

variable "db_instance_class" {
  description = "RDS instance class."
  type        = string
  default     = "db.t4g.micro"
}

variable "db_allocated_storage_gib" {
  description = "RDS gp3 storage in GiB."
  type        = number
  default     = 20
}

variable "db_multi_az" {
  description = "Run a Multi-AZ standby (roughly doubles the instance cost)."
  type        = bool
  default     = false
}

variable "db_backup_retention_days" {
  description = "Automated backup and point-in-time recovery window."
  type        = number
  default     = 7
}

variable "db_performance_insights_enabled" {
  description = "Enable Performance Insights (7-day free retention)."
  type        = bool
  default     = true
}

variable "deletion_protection" {
  description = "Protect RDS and the Cognito user pool from deletion. Set false (and apply) before terraform destroy."
  type        = bool
  default     = true
}

variable "skip_final_snapshot" {
  description = "Skip the final RDS snapshot on destroy. Keep false unless the data is disposable."
  type        = bool
  default     = false
}

# ---------------------------------------------------------------------------
# Compute
# ---------------------------------------------------------------------------

variable "api_cpu" {
  description = "API task CPU units."
  type        = number
  default     = 512
}

variable "api_memory" {
  description = "API task memory (MiB)."
  type        = number
  default     = 1024
}

variable "worker_cpu" {
  description = "Worker and one-off task CPU units."
  type        = number
  default     = 256
}

variable "worker_memory" {
  description = "Worker and one-off task memory (MiB)."
  type        = number
  default     = 512
}

variable "api_desired_count" {
  description = "API tasks. The realtime WebSocket hub is single-instance; more than one API task needs a shared fanout (Redis) first."
  type        = number
  default     = 1

  validation {
    condition     = var.api_desired_count >= 0 && var.api_desired_count <= 1
    error_message = "api_desired_count must be 0 or 1 until realtime fanout supports multiple API instances (Redis or equivalent)."
  }
}

variable "processor_desired_count" {
  description = "Processor worker tasks (outbox publisher + game-events consumer)."
  type        = number
  default     = 1
}

variable "delivery_desired_count" {
  description = "Delivery worker tasks."
  type        = number
  default     = 1
}

variable "reconciliation_desired_count" {
  description = "Reconciliation worker tasks."
  type        = number
  default     = 1
}

variable "replay_desired_count" {
  description = "Real-game replay workers (docs/adr/0015-real-game-replay.md). One is enough for a demo."
  type        = number
  default     = 1

  validation {
    condition     = var.replay_desired_count >= 0 && var.replay_desired_count <= 1
    error_message = "Run zero or one replay worker; sessions are shared state, one poller is enough."
  }
}

variable "replay_dataset" {
  description = "nba_data dataset the replay-import task downloads (cdnnba_po_2025 is the 2026 playoffs)."
  type        = string
  default     = "cdnnba_po_2025"

  validation {
    condition     = can(regex("^cdnnba(_po)?_20[0-9]{2}$", var.replay_dataset))
    error_message = "Use a dataset name such as cdnnba_po_2025 or cdnnba_2025."
  }
}

variable "ingestor_desired_count" {
  description = "Live ingestor tasks. Keep 0 until the provider key secret has a value."
  type        = number
  default     = 0
}

variable "worker_capacity_provider" {
  description = "Capacity provider for worker services. FARGATE_SPOT is roughly 70% cheaper; interruptions are tolerated by lease recovery and SQS redelivery. The API always uses FARGATE."
  type        = string
  default     = "FARGATE_SPOT"

  validation {
    condition     = contains(["FARGATE", "FARGATE_SPOT"], var.worker_capacity_provider)
    error_message = "worker_capacity_provider must be FARGATE or FARGATE_SPOT."
  }
}

variable "enable_container_insights" {
  description = "Enable ECS Container Insights (billed custom metrics and logs)."
  type        = bool
  default     = false
}

variable "enable_execute_command" {
  description = "Enable ECS Exec on services (adds SSM permissions)."
  type        = bool
  default     = false
}

variable "readonly_root_filesystem" {
  description = "Run containers with a read-only root filesystem and writable /tmp."
  type        = bool
  default     = true
}

variable "log_retention_days" {
  description = "CloudWatch log retention for every log group."
  type        = number
  default     = 14

  validation {
    condition     = contains([1, 3, 5, 7, 14, 30, 60, 90, 120, 150, 180, 365], var.log_retention_days)
    error_message = "log_retention_days must be a CloudWatch-supported value up to 365."
  }
}

variable "api_db_pool_size" {
  description = "API Hikari pool size (COURTPULSE_API_DB_POOL_SIZE)."
  type        = number
  default     = 5
}

variable "worker_db_pool_size" {
  description = "Worker Hikari pool size (Spring relaxed binding SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE)."
  type        = number
  default     = 4
}

variable "ingest_poll_interval" {
  description = "Provider poll interval for the ingestor."
  type        = string
  default     = "15s"
}

variable "provider_requests_per_minute" {
  description = "Client-side request budget for the data provider. BALLDONTLIE tiers: free 5, ALL-STAR 60, GOAT 600 requests/minute; play-by-play requires GOAT."
  type        = number
  default     = 30

  validation {
    condition     = var.provider_requests_per_minute >= 1 && var.provider_requests_per_minute <= 600
    error_message = "provider_requests_per_minute must be between 1 and 600."
  }
}

variable "provider_base_url" {
  description = "Base URL of the live data provider."
  type        = string
  default     = "https://api.balldontlie.io"

  validation {
    condition     = can(regex("^https://", var.provider_base_url))
    error_message = "provider_base_url must use HTTPS."
  }
}

variable "secret_recovery_window_days" {
  description = "Recovery window for the provider-key secret after deletion (0 deletes immediately)."
  type        = number
  default     = 7
}

# ---------------------------------------------------------------------------
# Tracing (optional)
# ---------------------------------------------------------------------------

variable "enable_tracing" {
  description = "Add an AWS Distro for OpenTelemetry sidecar and export sampled traces to X-Ray."
  type        = bool
  default     = false
}

variable "otel_collector_image" {
  description = "Pinned ADOT collector image."
  type        = string
  default     = "public.ecr.aws/aws-observability/aws-otel-collector:v0.50.0"
}

variable "trace_sampling_ratio" {
  description = "Root-span sampling ratio when tracing is enabled (worker polls create a trace each)."
  type        = number
  default     = 0.1

  validation {
    condition     = var.trace_sampling_ratio >= 0 && var.trace_sampling_ratio <= 1
    error_message = "trace_sampling_ratio must be between 0 and 1."
  }
}

# ---------------------------------------------------------------------------
# Identity and email
# ---------------------------------------------------------------------------

variable "allow_self_signup" {
  description = "Allow public Cognito self-registration (false = admin-created users only)."
  type        = bool
  default     = false
}

variable "extra_callback_urls" {
  description = "Additional Cognito callback URLs."
  type        = list(string)
  default     = []
}

variable "extra_logout_urls" {
  description = "Additional Cognito sign-out URLs."
  type        = list(string)
  default     = []
}

variable "email_from_address" {
  description = "Sender address for alert emails; SES sends a verification link to it."
  type        = string

  validation {
    condition     = can(regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$", var.email_from_address))
    error_message = "email_from_address must be an email address."
  }
}

# ---------------------------------------------------------------------------
# Observability and cost
# ---------------------------------------------------------------------------

variable "alarm_email" {
  description = "Optional email for CloudWatch alarm notifications."
  type        = string
  default     = null
}

variable "enable_alarm_topic_cmk" {
  description = "Encrypt the alarm topic with a customer-managed KMS key (about $1/month)."
  type        = bool
  default     = false
}

variable "db_connections_alarm_threshold" {
  description = "RDS DatabaseConnections alarm threshold."
  type        = number
  default     = 60
}

variable "monthly_budget_usd" {
  description = "Monthly account-wide AWS Budget in USD."
  type        = number
  default     = 25
}

variable "budget_email" {
  description = "Email for budget notifications."
  type        = string
}

variable "enable_nightly_shutdown" {
  description = "Scale services to zero and stop RDS on a schedule."
  type        = bool
  default     = false
}

variable "schedule_timezone" {
  description = "IANA time zone for shutdown schedules."
  type        = string
  default     = "America/New_York"
}

variable "shutdown_schedule" {
  description = "Scheduler expression for the nightly shutdown."
  type        = string
  default     = "cron(0 23 * * ? *)"
}

variable "database_start_schedule" {
  description = "Scheduler expression for starting RDS."
  type        = string
  default     = "cron(30 7 ? * MON-FRI *)"
}

variable "services_start_schedule" {
  description = "Scheduler expression for restoring service counts (after RDS is available)."
  type        = string
  default     = "cron(45 7 ? * MON-FRI *)"
}
