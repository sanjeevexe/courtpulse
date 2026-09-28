variable "name_prefix" {
  description = "Prefix for queue names, for example courtpulse-staging."
  type        = string
}

variable "max_receive_count" {
  description = "Receives before SQS redrives a message to its dead-letter queue."
  type        = number
  default     = 3
}

variable "message_retention_seconds" {
  description = "Source queue retention (4 days)."
  type        = number
  default     = 345600
}

variable "dlq_message_retention_seconds" {
  description = "Dead-letter queue retention (14 days, the SQS maximum). FIFO redrive keeps the original enqueue time, so this must exceed the source retention."
  type        = number
  default     = 1209600
}

variable "receive_wait_time_seconds" {
  description = "Default long-poll wait for receives that do not specify one."
  type        = number
  default     = 2
}

variable "game_events_visibility_timeout_seconds" {
  description = "Visibility timeout for game-events.fifo (above expected processor handler time)."
  type        = number
  default     = 30
}

variable "alert_deliveries_visibility_timeout_seconds" {
  description = "Visibility timeout for alert-deliveries.fifo (above the email send timeout)."
  type        = number
  default     = 35
}
