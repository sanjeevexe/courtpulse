variable "name_prefix" {
  description = "Prefix for resource names, for example courtpulse-staging."
  type        = string
}

variable "vpc_cidr" {
  description = "IPv4 CIDR block for the VPC. Four /20 subnets are carved from it."
  type        = string
  default     = "10.40.0.0/16"

  validation {
    condition     = can(cidrnetmask(var.vpc_cidr)) && tonumber(split("/", var.vpc_cidr)[1]) <= 18
    error_message = "vpc_cidr must be a valid IPv4 CIDR of /18 or larger."
  }
}

variable "api_container_port" {
  description = "Port the API container listens on."
  type        = number
  default     = 8080
}

variable "database_port" {
  description = "PostgreSQL port."
  type        = number
  default     = 5432
}

variable "alb_listener_port" {
  description = "ALB listener port CloudFront connects to: 80 for the HTTP origin, 443 when a certificate is supplied."
  type        = number
  default     = 80

  validation {
    condition     = contains([80, 443], var.alb_listener_port)
    error_message = "alb_listener_port must be 80 or 443."
  }
}
