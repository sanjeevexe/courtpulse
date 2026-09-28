variable "name_prefix" {
  description = "Prefix for resource names, for example courtpulse-staging."
  type        = string
}

variable "project" {
  description = "Project name; ECR repositories are <project>-api and <project>-worker."
  type        = string
}

variable "github_repository" {
  description = "GitHub repository allowed to assume the deploy role, as owner/name."
  type        = string

  validation {
    condition     = can(regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$", var.github_repository))
    error_message = "github_repository must look like owner/name."
  }
}

variable "github_environment" {
  description = "GitHub Actions environment whose jobs may assume the deploy role."
  type        = string
  default     = "staging"
}

variable "create_github_oidc_provider" {
  description = "Create the account's GitHub OIDC provider. Set false if another stack already created it (one per account)."
  type        = bool
  default     = true
}

variable "ecr_force_delete" {
  description = "Allow terraform destroy to delete repositories that still contain images."
  type        = bool
  default     = false
}

variable "ecr_keep_tagged_images" {
  description = "Tagged images to keep per repository (rollback depth)."
  type        = number
  default     = 20
}

variable "ecr_untagged_expiry_days" {
  description = "Days before untagged manifests expire. ECR never expires a manifest still referenced by a tagged image index (buildx attestations)."
  type        = number
  default     = 7
}

variable "cluster_name" {
  description = "ECS cluster name (ARNs are derived so this module has no dependency on the cluster)."
  type        = string
}

variable "service_names" {
  description = "ECS service names the deploy role may describe and update."
  type        = list(string)
}

variable "task_definition_families" {
  description = "All task definition families the deploy role may register revisions in."
  type        = list(string)
}

variable "oneoff_task_families" {
  description = "Task definition families the deploy role may run with RunTask (migrate, canary)."
  type        = list(string)
}

variable "passable_role_arns" {
  description = "Task and execution role ARNs the deploy role may pass to ECS when registering revisions."
  type        = list(string)
}

variable "web_bucket_name" {
  description = "SPA bucket the deploy role may sync."
  type        = string
}

variable "oneoff_log_group_name" {
  description = "Log group of the one-off tasks, readable by the deploy role."
  type        = string
}
