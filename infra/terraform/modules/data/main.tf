locals {
  identifier       = coalesce(var.identifier, "${var.name_prefix}-postgres")
  parameter_family = "postgres${split(".", var.engine_version)[0]}"
}

resource "aws_db_subnet_group" "this" {
  name        = "${var.name_prefix}-database"
  description = "Private subnets for ${var.name_prefix} PostgreSQL"
  subnet_ids  = var.private_subnet_ids
}

resource "aws_db_parameter_group" "this" {
  name_prefix = "${var.name_prefix}-postgres-"
  family      = local.parameter_family
  description = "CourtPulse PostgreSQL: TLS required and slow statements logged"

  # Reject every non-TLS client connection. The JDBC URL uses sslmode=require.
  parameter {
    name         = "rds.force_ssl"
    value        = "1"
    apply_method = "immediate"
  }

  parameter {
    name         = "log_min_duration_statement"
    value        = tostring(var.slow_query_threshold_ms)
    apply_method = "immediate"
  }

  lifecycle {
    create_before_destroy = true
  }
}

# Pre-create the export log group so RDS does not create one with infinite retention.
#trivy:ignore:AWS-0017 CloudWatch Logs default encryption is used; a customer-managed KMS key would add a monthly charge.
resource "aws_cloudwatch_log_group" "postgresql" {
  name              = "/aws/rds/instance/${local.identifier}/postgresql"
  retention_in_days = var.log_retention_days
}

#trivy:ignore:AWS-0176 IAM database authentication is not used; the application authenticates with the RDS-managed secret injected by ECS.
#trivy:ignore:AWS-0078 Performance Insights uses the AWS managed key to avoid a customer-managed KMS key charge.
resource "aws_db_instance" "this" {
  identifier     = local.identifier
  engine         = "postgres"
  engine_version = var.engine_version
  instance_class = var.instance_class

  db_name  = var.database_name
  username = var.master_username

  # RDS generates the password, stores it in Secrets Manager (aws/secretsmanager
  # key), and rotates it. Terraform state never contains the value.
  manage_master_user_password = true

  storage_type          = "gp3"
  allocated_storage     = var.allocated_storage_gib
  max_allocated_storage = var.max_allocated_storage_gib > 0 ? var.max_allocated_storage_gib : null
  storage_encrypted     = true

  db_subnet_group_name   = aws_db_subnet_group.this.name
  vpc_security_group_ids = var.security_group_ids
  publicly_accessible    = false
  multi_az               = var.multi_az
  port                   = 5432
  network_type           = "IPV4"

  parameter_group_name = aws_db_parameter_group.this.name

  backup_retention_period  = var.backup_retention_days
  backup_window            = var.backup_window
  maintenance_window       = var.maintenance_window
  copy_tags_to_snapshot    = true
  delete_automated_backups = true

  deletion_protection       = var.deletion_protection
  skip_final_snapshot       = var.skip_final_snapshot
  final_snapshot_identifier = var.skip_final_snapshot ? null : "${local.identifier}-final"

  auto_minor_version_upgrade  = true
  allow_major_version_upgrade = false
  apply_immediately           = var.apply_immediately

  performance_insights_enabled          = var.performance_insights_enabled
  performance_insights_retention_period = var.performance_insights_enabled ? 7 : null

  # Enhanced Monitoring writes to CloudWatch Logs and is billed; keep it off.
  monitoring_interval = 0

  enabled_cloudwatch_logs_exports = ["postgresql"]

  depends_on = [aws_cloudwatch_log_group.postgresql]
}
