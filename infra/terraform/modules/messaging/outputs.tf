output "game_events_queue_name" {
  description = "Name of the game-events FIFO queue (resolved by workers with GetQueueUrl)."
  value       = aws_sqs_queue.source["game_events"].name
}

output "game_events_queue_arn" {
  description = "ARN of the game-events FIFO queue."
  value       = aws_sqs_queue.source["game_events"].arn
}

output "game_events_dlq_name" {
  description = "Name of the game-events dead-letter queue."
  value       = aws_sqs_queue.dlq["game_events"].name
}

output "game_events_dlq_arn" {
  description = "ARN of the game-events dead-letter queue."
  value       = aws_sqs_queue.dlq["game_events"].arn
}

output "alert_deliveries_queue_name" {
  description = "Name of the alert-deliveries FIFO queue."
  value       = aws_sqs_queue.source["alert_deliveries"].name
}

output "alert_deliveries_queue_arn" {
  description = "ARN of the alert-deliveries FIFO queue."
  value       = aws_sqs_queue.source["alert_deliveries"].arn
}

output "alert_deliveries_dlq_name" {
  description = "Name of the alert-deliveries dead-letter queue."
  value       = aws_sqs_queue.dlq["alert_deliveries"].name
}

output "alert_deliveries_dlq_arn" {
  description = "ARN of the alert-deliveries dead-letter queue."
  value       = aws_sqs_queue.dlq["alert_deliveries"].arn
}

output "all_queue_arns" {
  description = "ARNs of all four queues (for metadata-only GetQueueUrl grants)."
  value       = [for queue in local.all_queues : queue.arn]
}
