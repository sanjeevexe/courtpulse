output "alarm_topic_arn" {
  description = "SNS topic receiving every alarm and OK transition."
  value       = aws_sns_topic.alarms.arn
}

output "dashboard_name" {
  description = "CloudWatch dashboard name."
  value       = aws_cloudwatch_dashboard.this.dashboard_name
}

output "alarm_names" {
  description = "Names of all alarms created by this module."
  value = concat(
    [
      aws_cloudwatch_metric_alarm.game_events_age.alarm_name,
      aws_cloudwatch_metric_alarm.alb_5xx.alarm_name,
      aws_cloudwatch_metric_alarm.alb_unhealthy_hosts.alarm_name,
      aws_cloudwatch_metric_alarm.alb_latency_p95.alarm_name,
      aws_cloudwatch_metric_alarm.rds_cpu.alarm_name,
      aws_cloudwatch_metric_alarm.rds_free_storage.alarm_name,
      aws_cloudwatch_metric_alarm.rds_connections.alarm_name,
      aws_cloudwatch_metric_alarm.api_error_logs.alarm_name,
    ],
    [for alarm in aws_cloudwatch_metric_alarm.dlq_not_empty : alarm.alarm_name],
  )
}
