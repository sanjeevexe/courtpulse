variable "name_prefix" {
  description = "Prefix for resource names, for example courtpulse-staging."
  type        = string
}

variable "monthly_budget_usd" {
  description = "Monthly account-wide cost budget in USD. A budget alerts; it never stops spending."
  type        = number
  default     = 25

  validation {
    condition     = var.monthly_budget_usd > 0
    error_message = "monthly_budget_usd must be positive."
  }
}

variable "budget_email" {
  description = "Email address that receives budget notifications."
  type        = string

  validation {
    condition     = can(regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$", var.budget_email))
    error_message = "budget_email must be an email address."
  }
}

variable "enable_nightly_shutdown" {
  description = "Create EventBridge Scheduler schedules that scale ECS services to zero and stop RDS overnight."
  type        = bool
  default     = false
}

variable "schedule_timezone" {
  description = "IANA time zone for all schedules."
  type        = string
  default     = "America/New_York"
}

variable "shutdown_schedule" {
  description = "When to scale services to zero and stop RDS."
  type        = string
  default     = "cron(0 23 * * ? *)"
}

variable "database_start_schedule" {
  description = "When to start RDS (before services, which fail readiness without it)."
  type        = string
  default     = "cron(30 7 ? * MON-FRI *)"
}

variable "services_start_schedule" {
  description = "When to restore service desired counts (after RDS is available, typically 10-15 minutes later)."
  type        = string
  default     = "cron(45 7 ? * MON-FRI *)"
}

variable "cluster_name" {
  description = "ECS cluster name."
  type        = string
}

variable "cluster_arn" {
  description = "ECS cluster ARN."
  type        = string
}

variable "services" {
  description = "Map of short name to { name, desired_count } for every service the schedule controls."
  type = map(object({
    name          = string
    desired_count = number
  }))
  default = {}
}

variable "db_instance_identifier" {
  description = "RDS instance identifier."
  type        = string
}

variable "db_instance_arn" {
  description = "RDS instance ARN."
  type        = string
}
