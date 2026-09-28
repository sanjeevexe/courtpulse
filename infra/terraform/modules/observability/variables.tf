variable "name_prefix" {
  description = "Prefix for resource names, for example courtpulse-staging."
  type        = string
}

variable "alarm_email" {
  description = "Optional email subscribed to the alarm topic (the recipient must confirm the subscription)."
  type        = string
  default     = null
}

variable "enable_topic_cmk" {
  description = "Encrypt the alarm topic with a customer-managed KMS key (about $1/month). The AWS managed aws/sns key cannot be used: CloudWatch cannot publish to topics encrypted with it."
  type        = bool
  default     = false
}

variable "game_events_queue_name" {
  description = "game-events FIFO queue name."
  type        = string
}

variable "game_events_dlq_name" {
  description = "game-events DLQ name."
  type        = string
}

variable "alert_deliveries_queue_name" {
  description = "alert-deliveries FIFO queue name."
  type        = string
}

variable "alert_deliveries_dlq_name" {
  description = "alert-deliveries DLQ name."
  type        = string
}

variable "alb_arn_suffix" {
  description = "ALB ARN suffix (CloudWatch LoadBalancer dimension)."
  type        = string
}

variable "target_group_arn_suffix" {
  description = "API target group ARN suffix (CloudWatch TargetGroup dimension)."
  type        = string
}

variable "db_instance_identifier" {
  description = "RDS instance identifier."
  type        = string
}

variable "cluster_name" {
  description = "ECS cluster name."
  type        = string
}

variable "service_names" {
  description = "Map of short process name to ECS service name, for dashboard widgets."
  type        = map(string)
}

variable "api_log_group_name" {
  description = "API CloudWatch log group for the error-count metric filter."
  type        = string
}

variable "metric_namespace" {
  description = "Namespace for log-derived custom metrics."
  type        = string
  default     = "CourtPulse/Staging"
}

variable "queue_age_threshold_seconds" {
  description = "Alarm when the oldest game-events message is older than this for five minutes."
  type        = number
  default     = 120
}

variable "alb_5xx_threshold" {
  description = "Alarm when target 5xx responses exceed this count in five minutes."
  type        = number
  default     = 5
}

variable "latency_p95_threshold_seconds" {
  description = "Alarm when ALB target p95 response time exceeds this."
  type        = number
  default     = 1
}

variable "db_cpu_threshold_percent" {
  description = "RDS CPU alarm threshold."
  type        = number
  default     = 80
}

variable "db_free_storage_threshold_bytes" {
  description = "Alarm when free storage drops below this (2 GiB)."
  type        = number
  default     = 2147483648
}

variable "db_connections_threshold" {
  description = "RDS connection alarm threshold. db.t4g.micro allows roughly LEAST(DBInstanceClassMemory/9531392, 5000), on the order of 80-110 connections, so alarm well before exhaustion."
  type        = number
  default     = 60
}

variable "api_error_log_threshold" {
  description = "Alarm when API ERROR log lines in five minutes exceed this."
  type        = number
  default     = 0
}
