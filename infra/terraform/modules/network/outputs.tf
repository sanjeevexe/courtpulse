output "vpc_id" {
  description = "VPC ID."
  value       = aws_vpc.this.id
}

output "availability_zones" {
  description = "The two availability zones in use."
  value       = local.azs
}

output "public_subnet_ids" {
  description = "Public subnet IDs (ALB and Fargate tasks with public IPs)."
  value       = aws_subnet.public[*].id
}

output "private_subnet_ids" {
  description = "Private subnet IDs (RDS only; no internet route)."
  value       = aws_subnet.private[*].id
}

output "alb_security_group_id" {
  description = "Security group for the ALB."
  value       = aws_security_group.alb.id
}

output "api_security_group_id" {
  description = "Security group for API tasks."
  value       = aws_security_group.api.id
}

output "worker_security_group_id" {
  description = "Security group for worker services and one-off tasks."
  value       = aws_security_group.worker.id
}

output "database_security_group_id" {
  description = "Security group for RDS."
  value       = aws_security_group.database.id
}
