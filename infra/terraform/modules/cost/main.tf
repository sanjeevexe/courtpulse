# Cost guardrails. The budget covers the whole account (not just tagged
# resources) so an unexpected charge anywhere is reported. Tag-filtered
# budgets require activating cost allocation tags first and lag by a day.

resource "aws_budgets_budget" "monthly" {
  name         = "${var.name_prefix}-monthly-cost"
  budget_type  = "COST"
  limit_amount = format("%.2f", var.monthly_budget_usd)
  limit_unit   = "USD"
  time_unit    = "MONTHLY"

  dynamic "notification" {
    for_each = {
      actual-50      = { threshold = 50, type = "ACTUAL" }
      actual-80      = { threshold = 80, type = "ACTUAL" }
      actual-100     = { threshold = 100, type = "ACTUAL" }
      forecasted-100 = { threshold = 100, type = "FORECASTED" }
    }

    content {
      comparison_operator        = "GREATER_THAN"
      threshold                  = notification.value.threshold
      threshold_type             = "PERCENTAGE"
      notification_type          = notification.value.type
      subscriber_email_addresses = [var.budget_email]
    }
  }
}

# ---------------------------------------------------------------------------
# Optional nightly shutdown (EventBridge Scheduler universal targets)
#
# Stopping saves Fargate and RDS instance-hours and the tasks' public IPv4
# charges. The ALB, its public IPv4 addresses, RDS storage, and secrets keep
# billing while "off"; only terraform destroy stops those. RDS starts itself
# again after seven days stopped, so a weekday start schedule is required.
# A terraform apply while services are scaled down restores Terraform's
# desired counts.
# ---------------------------------------------------------------------------

data "aws_iam_policy_document" "scheduler_assume" {
  count = var.enable_nightly_shutdown ? 1 : 0

  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["scheduler.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [split(":", var.cluster_arn)[4]]
    }
  }
}

resource "aws_iam_role" "scheduler" {
  count = var.enable_nightly_shutdown ? 1 : 0

  name               = "${var.name_prefix}-nightly-scheduler"
  description        = "EventBridge Scheduler: scale ${var.name_prefix} services and stop/start RDS"
  assume_role_policy = data.aws_iam_policy_document.scheduler_assume[0].json
}

locals {
  service_arns = [
    for service in var.services :
    "arn:aws:ecs:${split(":", var.cluster_arn)[3]}:${split(":", var.cluster_arn)[4]}:service/${var.cluster_name}/${service.name}"
  ]
}

data "aws_iam_policy_document" "scheduler" {
  count = var.enable_nightly_shutdown ? 1 : 0

  statement {
    sid       = "ScaleServices"
    actions   = ["ecs:UpdateService"]
    resources = local.service_arns
  }

  statement {
    sid       = "StopStartDatabase"
    actions   = ["rds:StopDBInstance", "rds:StartDBInstance"]
    resources = [var.db_instance_arn]
  }
}

resource "aws_iam_role_policy" "scheduler" {
  count = var.enable_nightly_shutdown ? 1 : 0

  name   = "nightly-shutdown"
  role   = aws_iam_role.scheduler[0].id
  policy = data.aws_iam_policy_document.scheduler[0].json
}

resource "aws_scheduler_schedule" "services_stop" {
  for_each = var.enable_nightly_shutdown ? var.services : {}

  name                         = "${var.name_prefix}-${each.key}-stop"
  description                  = "Scale ${each.value.name} to zero"
  schedule_expression          = var.shutdown_schedule
  schedule_expression_timezone = var.schedule_timezone

  flexible_time_window {
    mode = "OFF"
  }

  target {
    arn      = "arn:aws:scheduler:::aws-sdk:ecs:updateService"
    role_arn = aws_iam_role.scheduler[0].arn
    input = jsonencode({
      Cluster      = var.cluster_name
      Service      = each.value.name
      DesiredCount = 0
    })

    retry_policy {
      maximum_retry_attempts = 3
    }
  }
}

resource "aws_scheduler_schedule" "services_start" {
  for_each = var.enable_nightly_shutdown ? var.services : {}

  name                         = "${var.name_prefix}-${each.key}-start"
  description                  = "Restore ${each.value.name} to ${each.value.desired_count} tasks"
  schedule_expression          = var.services_start_schedule
  schedule_expression_timezone = var.schedule_timezone

  flexible_time_window {
    mode = "OFF"
  }

  target {
    arn      = "arn:aws:scheduler:::aws-sdk:ecs:updateService"
    role_arn = aws_iam_role.scheduler[0].arn
    input = jsonencode({
      Cluster      = var.cluster_name
      Service      = each.value.name
      DesiredCount = each.value.desired_count
    })

    retry_policy {
      maximum_retry_attempts = 3
    }
  }
}

resource "aws_scheduler_schedule" "database_stop" {
  count = var.enable_nightly_shutdown ? 1 : 0

  name                         = "${var.name_prefix}-database-stop"
  description                  = "Stop ${var.db_instance_identifier} overnight"
  schedule_expression          = var.shutdown_schedule
  schedule_expression_timezone = var.schedule_timezone

  flexible_time_window {
    mode = "OFF"
  }

  target {
    arn      = "arn:aws:scheduler:::aws-sdk:rds:stopDBInstance"
    role_arn = aws_iam_role.scheduler[0].arn
    input    = jsonencode({ DbInstanceIdentifier = var.db_instance_identifier })

    retry_policy {
      maximum_retry_attempts = 3
    }
  }
}

resource "aws_scheduler_schedule" "database_start" {
  count = var.enable_nightly_shutdown ? 1 : 0

  name                         = "${var.name_prefix}-database-start"
  description                  = "Start ${var.db_instance_identifier} before services"
  schedule_expression          = var.database_start_schedule
  schedule_expression_timezone = var.schedule_timezone

  flexible_time_window {
    mode = "OFF"
  }

  target {
    arn      = "arn:aws:scheduler:::aws-sdk:rds:startDBInstance"
    role_arn = aws_iam_role.scheduler[0].arn
    input    = jsonencode({ DbInstanceIdentifier = var.db_instance_identifier })

    retry_policy {
      maximum_retry_attempts = 3
    }
  }
}
