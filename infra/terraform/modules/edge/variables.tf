variable "name_prefix" {
  description = "Prefix for resource names, for example courtpulse-staging."
  type        = string
}

variable "vpc_id" {
  description = "VPC for the API target group."
  type        = string
}

variable "public_subnet_ids" {
  description = "Public subnets for the internet-facing ALB."
  type        = list(string)
}

variable "alb_security_group_id" {
  description = "ALB security group (ingress only from the CloudFront origin-facing prefix list)."
  type        = string
}

variable "api_container_port" {
  description = "API container port registered in the target group."
  type        = number
  default     = 8080
}

variable "api_health_check_path" {
  description = "Target health check path; readiness includes the database and schema."
  type        = string
  default     = "/actuator/health/readiness"
}

variable "api_path_patterns" {
  description = "Viewer paths CloudFront sends to the ALB. The ALB forwards only these (at most three to stay within ALB condition limits)."
  type        = list(string)
  default     = ["/api/*", "/ws/*", "/v3/api-docs*"]

  validation {
    condition     = length(var.api_path_patterns) >= 1 && length(var.api_path_patterns) <= 3
    error_message = "Use one to three API path patterns (ALB rules allow five condition values including the origin header)."
  }
}

variable "alb_certificate_arn" {
  description = "ACM certificate ARN for an HTTPS ALB listener. Null uses an HTTP:80 origin (no custom domain required)."
  type        = string
  default     = null
}

variable "alb_origin_domain_name" {
  description = "DNS name covered by alb_certificate_arn that resolves (CNAME/alias) to the ALB. Required with a certificate because CloudFront validates the origin certificate against it."
  type        = string
  default     = null
}

variable "alb_idle_timeout_seconds" {
  description = "ALB idle timeout; must exceed the WebSocket heartbeat interval (>= 75 seconds)."
  type        = number
  default     = 120

  validation {
    condition     = var.alb_idle_timeout_seconds >= 75 && var.alb_idle_timeout_seconds <= 4000
    error_message = "alb_idle_timeout_seconds must be between 75 and 4000."
  }
}

variable "deregistration_delay_seconds" {
  description = "Target group deregistration delay."
  type        = number
  default     = 30
}

variable "enable_alb_access_logs" {
  description = "Write ALB access logs to a dedicated S3 bucket (storage and request charges apply)."
  type        = bool
  default     = false
}

variable "alb_access_log_retention_days" {
  description = "Days to keep ALB access logs when enabled."
  type        = number
  default     = 14
}

variable "content_security_policy" {
  description = "Content-Security-Policy value applied to every CloudFront response."
  type        = string
}

variable "cloudfront_price_class" {
  description = "CloudFront price class. PriceClass_100 (North America and Europe edges) is the lowest cost."
  type        = string
  default     = "PriceClass_100"

  validation {
    condition     = contains(["PriceClass_100", "PriceClass_200", "PriceClass_All"], var.cloudfront_price_class)
    error_message = "cloudfront_price_class must be PriceClass_100, PriceClass_200, or PriceClass_All."
  }
}

variable "origin_read_timeout_seconds" {
  description = "CloudFront origin read timeout for API behaviors (60 is the maximum without a quota increase)."
  type        = number
  default     = 60
}

variable "web_bucket_force_destroy" {
  description = "Allow terraform destroy to delete the web bucket with its (reproducible) build output."
  type        = bool
  default     = true
}

variable "origin_verify_header_name" {
  description = "Custom header CloudFront adds and the ALB requires."
  type        = string
  default     = "X-CourtPulse-Origin-Verify"
}

variable "web_bucket_name" {
  description = "Globally unique name for the private SPA bucket (computed by the caller so IAM can reference it deterministically)."
  type        = string
}
