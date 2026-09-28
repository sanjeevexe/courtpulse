output "task_definition_family" {
  description = "Task definition family (CI registers new revisions in this family)."
  value       = aws_ecs_task_definition.this.family
}

output "task_definition_arn" {
  description = "ARN of the Terraform-registered revision (not necessarily the deployed one)."
  value       = aws_ecs_task_definition.this.arn
}

output "service_name" {
  description = "ECS service name, or null for one-off task families."
  value       = var.create_service ? aws_ecs_service.this[0].name : null
}

output "service_arn" {
  description = "ECS service ARN, or null for one-off task families."
  value       = var.create_service ? aws_ecs_service.this[0].id : null
}

output "task_role_arn" {
  description = "Application task role ARN."
  value       = aws_iam_role.task.arn
}

output "execution_role_arn" {
  description = "Task execution role ARN."
  value       = aws_iam_role.execution.arn
}

output "log_group_name" {
  description = "CloudWatch log group used by the containers."
  value       = local.log_group_name
}

output "container_name" {
  description = "Name of the application container (the one CI re-images)."
  value       = local.container_name
}

output "container_definitions" {
  description = "Structured container definitions as Terraform renders them (used by the offline plan tests)."
  value       = local.container_definitions
}
