output "instance_identifier" {
  description = "RDS instance identifier."
  value       = aws_db_instance.this.identifier
}

output "instance_arn" {
  description = "RDS instance ARN."
  value       = aws_db_instance.this.arn
}

output "address" {
  description = "DNS address of the instance (private; resolvable only inside the VPC)."
  value       = aws_db_instance.this.address
}

output "port" {
  description = "PostgreSQL port."
  value       = aws_db_instance.this.port
}

output "database_name" {
  description = "Initial database name."
  value       = aws_db_instance.this.db_name
}

output "jdbc_url" {
  description = "JDBC URL used by every CourtPulse process (TLS required)."
  value       = "jdbc:postgresql://${aws_db_instance.this.address}:${aws_db_instance.this.port}/${aws_db_instance.this.db_name}?sslmode=require"
}

output "master_user_secret_arn" {
  description = "ARN of the RDS-managed Secrets Manager secret (JSON keys username and password). The value is never in state."
  value       = aws_db_instance.this.master_user_secret[0].secret_arn
}

output "engine_version_actual" {
  description = "Engine version RDS is actually running."
  value       = aws_db_instance.this.engine_version_actual
}

output "parameter_group_name" {
  description = "DB parameter group name (reuse it when restoring beside the instance)."
  value       = aws_db_parameter_group.this.name
}

output "subnet_group_name" {
  description = "DB subnet group name (reuse it when restoring beside the instance)."
  value       = aws_db_subnet_group.this.name
}
