variable "name" {
  description = "Short process name, for example api, processor, or migrate. Used for the task family suffix, log stream prefix, and OTEL_SERVICE_NAME suffix."
  type        = string

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{1,24}$", var.name))
    error_message = "name must be 2-25 lowercase letters, digits, or hyphens."
  }
}

variable "name_prefix" {
  description = "Prefix for resource names, for example courtpulse-staging."
  type        = string
}

variable "create_service" {
  description = "Create a long-running ECS service. False registers only a task definition for one-off run-task use."
  type        = bool
  default     = true
}

variable "cluster_arn" {
  description = "ECS cluster ARN."
  type        = string
}

variable "image" {
  description = "Full image reference (repository URL and tag). The staging root refuses a full apply without one (aws_ecs_cluster precondition)."
  type        = string
  default     = null
}

variable "command" {
  description = "Container command override appended to the image ENTRYPOINT (null keeps the image CMD)."
  type        = list(string)
  default     = null
}

variable "cpu" {
  description = "Task CPU units (256 = 0.25 vCPU)."
  type        = number
  default     = 256
}

variable "memory" {
  description = "Task memory in MiB."
  type        = number
  default     = 512
}

variable "container_port" {
  description = "Container port to expose, or null for processes without a listener."
  type        = number
  default     = null
}

variable "environment" {
  description = "Plain environment variables. Never put secrets here."
  type        = map(string)
  default     = {}
}

variable "secrets" {
  description = "Environment variable name -> Secrets Manager valueFrom (ARN, optionally with a :json-key:: suffix)."
  type        = map(string)
  default     = {}
}

variable "execution_secret_arns" {
  description = "Secrets Manager secret ARNs (without JSON-key suffixes) the execution role may read to inject var.secrets."
  type        = list(string)
  default     = []
}

variable "ecr_repository_arn" {
  description = "ECR repository the execution role may pull from."
  type        = string
}

variable "task_role_name" {
  description = "Exact IAM role name for the task role (deterministic so the deploy role can scope iam:PassRole)."
  type        = string
}

variable "execution_role_name" {
  description = "Exact IAM role name for the task execution role."
  type        = string
}

variable "task_role_policies" {
  description = "Least-privilege inline policies for the application task role, keyed by a static name. Keys must be known at plan time; policy JSON may be unknown until apply."
  type        = map(string)
  default     = {}
}

variable "health_check" {
  description = "Container health check. Null disables it."
  type = object({
    command      = list(string)
    interval     = optional(number, 30)
    timeout      = optional(number, 5)
    retries      = optional(number, 3)
    start_period = optional(number, 60)
  })
  default = null
}

variable "load_balancer" {
  description = "Optional ALB target group attachment."
  type = object({
    target_group_arn                  = string
    health_check_grace_period_seconds = optional(number, 120)
  })
  default = null
}

variable "desired_count" {
  description = "Desired running task count."
  type        = number
  default     = 1
}

variable "capacity_provider" {
  description = "FARGATE or FARGATE_SPOT."
  type        = string
  default     = "FARGATE"

  validation {
    condition     = contains(["FARGATE", "FARGATE_SPOT"], var.capacity_provider)
    error_message = "capacity_provider must be FARGATE or FARGATE_SPOT."
  }
}

variable "deployment_minimum_healthy_percent" {
  description = "Rolling deployment lower bound."
  type        = number
  default     = 100
}

variable "deployment_maximum_percent" {
  description = "Rolling deployment upper bound."
  type        = number
  default     = 200
}

variable "subnet_ids" {
  description = "Subnets for task ENIs."
  type        = list(string)
}

variable "security_group_ids" {
  description = "Security groups for task ENIs."
  type        = list(string)
}

variable "assign_public_ip" {
  description = "Give task ENIs a public IPv4 address (required for egress without NAT; billed hourly)."
  type        = bool
  default     = true
}

variable "log_group_name" {
  description = "Existing log group to use. Null creates /ecs/<name_prefix>/<name>."
  type        = string
  default     = null
}

variable "log_retention_days" {
  description = "Retention for a log group created by this module."
  type        = number
  default     = 14
}

variable "enable_execute_command" {
  description = "Enable ECS Exec (adds SSM messages permissions to the task role)."
  type        = bool
  default     = false
}

variable "readonly_root_filesystem" {
  description = "Mount the container root filesystem read-only; /tmp is a writable task-storage volume."
  type        = bool
  default     = true
}

variable "stop_timeout_seconds" {
  description = "Seconds between SIGTERM and SIGKILL (Fargate maximum 120)."
  type        = number
  default     = 30
}

variable "tracing" {
  description = "Optional AWS Distro for OpenTelemetry sidecar exporting traces to X-Ray."
  type = object({
    enabled           = bool
    collector_image   = optional(string, "")
    sampling_ratio    = optional(number, 0.1)
    collector_memory  = optional(number, 128)
    service_namespace = optional(string, "courtpulse")
    environment_name  = optional(string, "staging")
  })
  default = {
    enabled = false
  }
}
