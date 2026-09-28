variable "name_prefix" {
  description = "Prefix for resource names, for example courtpulse-staging."
  type        = string
}

variable "domain_prefix" {
  description = "Cognito hosted-UI domain prefix. Computed by the caller so the CloudFront CSP can reference it without a dependency cycle."
  type        = string

  validation {
    condition     = can(regex("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$", var.domain_prefix)) && !can(regex("aws|amazon|cognito", var.domain_prefix))
    error_message = "domain_prefix must be lowercase alphanumeric/hyphens (max 63) and must not contain aws, amazon, or cognito."
  }
}

variable "web_origin" {
  description = "Browser origin of the SPA, for example https://d111111abcdef8.cloudfront.net (no trailing slash)."
  type        = string

  validation {
    condition     = can(regex("^https://[^/]+$", var.web_origin))
    error_message = "web_origin must be an https origin without a path."
  }
}

variable "extra_callback_urls" {
  description = "Additional exact redirect URIs (for example a future custom domain). Must be HTTPS except loopback."
  type        = list(string)
  default     = []
}

variable "extra_logout_urls" {
  description = "Additional exact sign-out URIs."
  type        = list(string)
  default     = []
}

variable "allow_self_signup" {
  description = "Allow public self-registration. False keeps staging admin-create-only."
  type        = bool
  default     = false
}

variable "deletion_protection" {
  description = "Protect the user pool from deletion."
  type        = bool
  default     = true
}

variable "operations_group_name" {
  description = "Cognito group whose members receive the operations authority (cognito:groups claim)."
  type        = string
  default     = "courtpulse-ops"
}
