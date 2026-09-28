variable "name_prefix" {
  description = "Prefix for resource names, for example courtpulse-staging."
  type        = string
}

variable "identifier" {
  description = "RDS instance identifier. Null uses <name_prefix>-postgres; set it only when adopting a point-in-time restore (docs/runbooks/database-restore.md)."
  type        = string
  default     = null

  validation {
    condition     = var.identifier == null || can(regex("^[a-z][a-z0-9-]{0,62}$", var.identifier))
    error_message = "identifier must start with a letter and contain only lowercase letters, digits, and hyphens (max 63)."
  }
}

variable "private_subnet_ids" {
  description = "Private subnets for the DB subnet group (two AZs)."
  type        = list(string)

  validation {
    condition     = length(var.private_subnet_ids) >= 2
    error_message = "RDS requires subnets in at least two availability zones."
  }
}

variable "security_group_ids" {
  description = "Security groups attached to the DB instance."
  type        = list(string)
}

variable "engine_version" {
  description = "PostgreSQL engine version. A full minor (17.11) pins; a major-only prefix (17) lets RDS choose and track the default minor without Terraform drift."
  type        = string
  default     = "17.11"

  validation {
    condition     = can(regex("^17(\\.[0-9]+)?$", var.engine_version))
    error_message = "engine_version must be PostgreSQL 17 (for example 17 or 17.11); the parameter group family is postgres17."
  }
}

variable "instance_class" {
  description = "RDS instance class."
  type        = string
  default     = "db.t4g.micro"
}

variable "allocated_storage_gib" {
  description = "Allocated gp3 storage in GiB."
  type        = number
  default     = 20

  validation {
    condition     = var.allocated_storage_gib >= 20
    error_message = "gp3 storage for RDS PostgreSQL must be at least 20 GiB."
  }
}

variable "max_allocated_storage_gib" {
  description = "Storage autoscaling ceiling in GiB. 0 disables autoscaling so storage cost cannot grow silently."
  type        = number
  default     = 0
}

variable "database_name" {
  description = "Initial database name."
  type        = string
  default     = "courtpulse"
}

variable "master_username" {
  description = "Master username. The password is generated and stored by RDS in Secrets Manager."
  type        = string
  default     = "courtpulse"
}

variable "multi_az" {
  description = "Create a standby in a second AZ (roughly doubles instance cost)."
  type        = bool
  default     = false
}

variable "backup_retention_days" {
  description = "Automated backup retention (also bounds point-in-time recovery)."
  type        = number
  default     = 7

  validation {
    condition     = var.backup_retention_days >= 1 && var.backup_retention_days <= 35
    error_message = "backup_retention_days must be 1-35 so point-in-time recovery stays enabled."
  }
}

variable "deletion_protection" {
  description = "Block deletion of the instance until explicitly disabled."
  type        = bool
  default     = true
}

variable "skip_final_snapshot" {
  description = "Skip the final snapshot on deletion. Keep false unless the data is disposable."
  type        = bool
  default     = false
}

variable "performance_insights_enabled" {
  description = "Enable Performance Insights with the free 7-day retention."
  type        = bool
  default     = true
}

variable "apply_immediately" {
  description = "Apply modifications immediately instead of in the maintenance window (reasonable for staging)."
  type        = bool
  default     = true
}

variable "log_retention_days" {
  description = "Retention for the exported PostgreSQL log group."
  type        = number
  default     = 14
}

variable "slow_query_threshold_ms" {
  description = "log_min_duration_statement in milliseconds."
  type        = number
  default     = 500
}

variable "backup_window" {
  description = "Daily UTC backup window."
  type        = string
  default     = "07:00-07:30"
}

variable "maintenance_window" {
  description = "Weekly UTC maintenance window; must not overlap the backup window."
  type        = string
  default     = "sun:08:00-sun:08:30"
}
