output "budget_name" {
  description = "Name of the monthly cost budget."
  value       = aws_budgets_budget.monthly.name
}

output "nightly_shutdown_enabled" {
  description = "Whether the nightly shutdown schedules exist."
  value       = var.enable_nightly_shutdown
}

output "scheduler_role_arn" {
  description = "IAM role used by the shutdown schedules, or null."
  value       = var.enable_nightly_shutdown ? aws_iam_role.scheduler[0].arn : null
}
