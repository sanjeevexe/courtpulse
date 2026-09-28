variable "region" {
  description = "AWS region that hosts the Terraform state bucket."
  type        = string
  default     = "us-east-1"
}

variable "project" {
  description = "Short lowercase project name used in resource names and tags."
  type        = string
  default     = "courtpulse"

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{1,20}$", var.project))
    error_message = "project must be 2-21 lowercase letters, digits, or hyphens and start with a letter."
  }
}

variable "owner" {
  description = "Owner tag value (a person or team responsible for the resources)."
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

variable "state_bucket_name" {
  description = "Optional explicit state bucket name. Null derives <project>-tfstate-<account>-<region>, which is globally unique."
  type        = string
  default     = null

  validation {
    condition     = var.state_bucket_name == null || can(regex("^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$", var.state_bucket_name))
    error_message = "state_bucket_name must be a valid S3 bucket name."
  }
}

variable "noncurrent_version_retention_days" {
  description = "Days to keep superseded state versions for recovery before they expire."
  type        = number
  default     = 90

  validation {
    condition     = var.noncurrent_version_retention_days >= 7
    error_message = "Keep at least seven days of state history."
  }
}
