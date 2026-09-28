# Alarm -> runbook mapping (docs/runbooks/observability.md unless noted):
#
#   game-events-oldest-message-age  -> "Event or outbox lag"
#   game-events-dlq-not-empty       -> "Dead-letter queue"
#   alert-deliveries-dlq-not-empty  -> "Dead-letter queue", then "Delivery failures"
#   alb-target-5xx                  -> "API or scrape unavailable"
#   alb-unhealthy-hosts             -> "API or scrape unavailable" (readiness includes the database)
#   alb-target-p95-latency          -> "API or scrape unavailable"; check RDS alarms first
#   rds-cpu-high                    -> "Database pressure (AWS staging)"
#   rds-free-storage-low            -> "Database pressure (AWS staging)"; storage autoscaling is off by design
#   rds-connections-high            -> "Database pressure (AWS staging)"; compare Hikari pool sizes per service
#   api-error-logs                  -> "API or scrape unavailable"; read the API log group by correlation ID
#
# docs/deployment/aws-staging.md ("Alarm response") adds AWS-specific first steps.
# Ten standard-resolution alarms fit the CloudWatch free tier (10 alarms).

data "aws_caller_identity" "current" {}

data "aws_region" "current" {}

locals {
  account_id = data.aws_caller_identity.current.account_id
  region     = data.aws_region.current.region
  topic_name = "${var.name_prefix}-alarms"
}

# ---------------------------------------------------------------------------
# Alarm topic
# ---------------------------------------------------------------------------

data "aws_iam_policy_document" "topic_key" {
  count = var.enable_topic_cmk ? 1 : 0

  # Key policies must use "*" as the resource (it means this key).
  statement {
    sid       = "AccountAdministration"
    actions   = ["kms:*"]
    resources = ["*"]

    principals {
      type        = "AWS"
      identifiers = ["arn:aws:iam::${local.account_id}:root"]
    }
  }

  statement {
    sid       = "CloudWatchAlarmsPublish"
    actions   = ["kms:Decrypt", "kms:GenerateDataKey*"]
    resources = ["*"]

    principals {
      type        = "Service"
      identifiers = ["cloudwatch.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [local.account_id]
    }
  }
}

resource "aws_kms_key" "topic" {
  count = var.enable_topic_cmk ? 1 : 0

  description             = "${local.topic_name} SNS encryption (CloudWatch alarms can publish)"
  enable_key_rotation     = true
  deletion_window_in_days = 7
  policy                  = data.aws_iam_policy_document.topic_key[0].json
}

resource "aws_kms_alias" "topic" {
  count = var.enable_topic_cmk ? 1 : 0

  name          = "alias/${local.topic_name}"
  target_key_id = aws_kms_key.topic[0].key_id
}

# Encryption is opt-in because the free AWS managed aws/sns key silently
# breaks CloudWatch alarm delivery (its key policy cannot grant CloudWatch).
# Alarm notifications carry metric metadata only, never application data.
resource "aws_sns_topic" "alarms" {
  name              = local.topic_name
  kms_master_key_id = var.enable_topic_cmk ? aws_kms_key.topic[0].arn : null
}

data "aws_iam_policy_document" "topic" {
  statement {
    sid       = "AllowCloudWatchAlarms"
    actions   = ["sns:Publish"]
    resources = [aws_sns_topic.alarms.arn]

    principals {
      type        = "Service"
      identifiers = ["cloudwatch.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [local.account_id]
    }

    condition {
      test     = "ArnLike"
      variable = "aws:SourceArn"
      values   = ["arn:aws:cloudwatch:${local.region}:${local.account_id}:alarm:*"]
    }
  }

  statement {
    sid = "AccountManagement"
    actions = [
      "sns:GetTopicAttributes",
      "sns:SetTopicAttributes",
      "sns:Subscribe",
      "sns:ListSubscriptionsByTopic",
      "sns:Publish",
    ]
    resources = [aws_sns_topic.alarms.arn]

    principals {
      type        = "AWS"
      identifiers = ["arn:aws:iam::${local.account_id}:root"]
    }
  }

  statement {
    sid       = "DenyInsecureTransport"
    effect    = "Deny"
    actions   = ["sns:Publish"]
    resources = [aws_sns_topic.alarms.arn]

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }
}

resource "aws_sns_topic_policy" "alarms" {
  arn    = aws_sns_topic.alarms.arn
  policy = data.aws_iam_policy_document.topic.json
}

resource "aws_sns_topic_subscription" "email" {
  count = var.alarm_email == null ? 0 : 1

  topic_arn = aws_sns_topic.alarms.arn
  protocol  = "email"
  endpoint  = var.alarm_email
}

# ---------------------------------------------------------------------------
# Queue alarms
# ---------------------------------------------------------------------------

resource "aws_cloudwatch_metric_alarm" "game_events_age" {
  alarm_name          = "${var.name_prefix}-game-events-oldest-message-age"
  alarm_description   = "Oldest game-events message > ${var.queue_age_threshold_seconds}s for 5 minutes. Runbook: docs/runbooks/observability.md#event-or-outbox-lag"
  namespace           = "AWS/SQS"
  metric_name         = "ApproximateAgeOfOldestMessage"
  dimensions          = { QueueName = var.game_events_queue_name }
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 5
  datapoints_to_alarm = 5
  threshold           = var.queue_age_threshold_seconds
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alarms.arn]
  ok_actions          = [aws_sns_topic.alarms.arn]
}

resource "aws_cloudwatch_metric_alarm" "dlq_not_empty" {
  for_each = {
    game-events      = var.game_events_dlq_name
    alert-deliveries = var.alert_deliveries_dlq_name
  }

  alarm_name          = "${var.name_prefix}-${each.key}-dlq-not-empty"
  alarm_description   = "A message reached ${each.value}. Preserve it and diagnose before any redrive. Runbook: docs/runbooks/observability.md#dead-letter-queue"
  namespace           = "AWS/SQS"
  metric_name         = "ApproximateNumberOfMessagesVisible"
  dimensions          = { QueueName = each.value }
  statistic           = "Maximum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  # SQS stops publishing metrics for idle, empty queues; absence is healthy.
  treat_missing_data = "notBreaching"
  alarm_actions      = [aws_sns_topic.alarms.arn]
  ok_actions         = [aws_sns_topic.alarms.arn]
}

# ---------------------------------------------------------------------------
# ALB alarms
# ---------------------------------------------------------------------------

resource "aws_cloudwatch_metric_alarm" "alb_5xx" {
  alarm_name          = "${var.name_prefix}-alb-target-5xx"
  alarm_description   = "API returned more than ${var.alb_5xx_threshold} 5xx responses in 5 minutes. Runbook: docs/runbooks/observability.md#api-or-scrape-unavailable"
  namespace           = "AWS/ApplicationELB"
  metric_name         = "HTTPCode_Target_5XX_Count"
  dimensions          = { LoadBalancer = var.alb_arn_suffix, TargetGroup = var.target_group_arn_suffix }
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = var.alb_5xx_threshold
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alarms.arn]
  ok_actions          = [aws_sns_topic.alarms.arn]
}

resource "aws_cloudwatch_metric_alarm" "alb_unhealthy_hosts" {
  alarm_name          = "${var.name_prefix}-alb-unhealthy-hosts"
  alarm_description   = "An API target failed readiness (database or schema) for 3 minutes. Runbook: docs/runbooks/observability.md#api-or-scrape-unavailable"
  namespace           = "AWS/ApplicationELB"
  metric_name         = "UnHealthyHostCount"
  dimensions          = { LoadBalancer = var.alb_arn_suffix, TargetGroup = var.target_group_arn_suffix }
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 3
  datapoints_to_alarm = 3
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alarms.arn]
  ok_actions          = [aws_sns_topic.alarms.arn]
}

resource "aws_cloudwatch_metric_alarm" "alb_latency_p95" {
  alarm_name          = "${var.name_prefix}-alb-target-p95-latency"
  alarm_description   = "API p95 target response time > ${var.latency_p95_threshold_seconds}s for 10 minutes. Runbook: docs/runbooks/observability.md#api-or-scrape-unavailable"
  namespace           = "AWS/ApplicationELB"
  metric_name         = "TargetResponseTime"
  dimensions          = { LoadBalancer = var.alb_arn_suffix, TargetGroup = var.target_group_arn_suffix }
  extended_statistic  = "p95"
  period              = 300
  evaluation_periods  = 2
  datapoints_to_alarm = 2
  threshold           = var.latency_p95_threshold_seconds
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alarms.arn]
  ok_actions          = [aws_sns_topic.alarms.arn]
}

# ---------------------------------------------------------------------------
# RDS alarms (missing data while the instance is stopped is not an incident)
# ---------------------------------------------------------------------------

resource "aws_cloudwatch_metric_alarm" "rds_cpu" {
  alarm_name          = "${var.name_prefix}-rds-cpu-high"
  alarm_description   = "RDS CPU > ${var.db_cpu_threshold_percent}% for 15 minutes (t4g burst credits may be exhausted). Runbook: docs/runbooks/observability.md#database-pressure-aws-staging"
  namespace           = "AWS/RDS"
  metric_name         = "CPUUtilization"
  dimensions          = { DBInstanceIdentifier = var.db_instance_identifier }
  statistic           = "Average"
  period              = 300
  evaluation_periods  = 3
  datapoints_to_alarm = 3
  threshold           = var.db_cpu_threshold_percent
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alarms.arn]
  ok_actions          = [aws_sns_topic.alarms.arn]
}

resource "aws_cloudwatch_metric_alarm" "rds_free_storage" {
  alarm_name          = "${var.name_prefix}-rds-free-storage-low"
  alarm_description   = "RDS free storage below ${floor(var.db_free_storage_threshold_bytes / 1073741824)} GiB. Runbook: docs/runbooks/observability.md#database-pressure-aws-staging"
  namespace           = "AWS/RDS"
  metric_name         = "FreeStorageSpace"
  dimensions          = { DBInstanceIdentifier = var.db_instance_identifier }
  statistic           = "Minimum"
  period              = 300
  evaluation_periods  = 1
  threshold           = var.db_free_storage_threshold_bytes
  comparison_operator = "LessThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alarms.arn]
  ok_actions          = [aws_sns_topic.alarms.arn]
}

resource "aws_cloudwatch_metric_alarm" "rds_connections" {
  alarm_name          = "${var.name_prefix}-rds-connections-high"
  alarm_description   = "RDS connections > ${var.db_connections_threshold} for 10 minutes (pool leak or too many tasks). Runbook: docs/runbooks/observability.md#database-pressure-aws-staging"
  namespace           = "AWS/RDS"
  metric_name         = "DatabaseConnections"
  dimensions          = { DBInstanceIdentifier = var.db_instance_identifier }
  statistic           = "Maximum"
  period              = 300
  evaluation_periods  = 2
  datapoints_to_alarm = 2
  threshold           = var.db_connections_threshold
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alarms.arn]
  ok_actions          = [aws_sns_topic.alarms.arn]
}

# ---------------------------------------------------------------------------
# API error log lines -> custom metric -> alarm
# JSON logs match $.level = "ERROR"; plain Spring console lines match " ERROR ".
# Both filters feed the same metric.
# ---------------------------------------------------------------------------

resource "aws_cloudwatch_log_metric_filter" "api_errors" {
  for_each = {
    json = "{ $.level = \"ERROR\" }"
    text = "\" ERROR \""
  }

  name           = "${var.name_prefix}-api-error-lines-${each.key}"
  log_group_name = var.api_log_group_name
  pattern        = each.value

  metric_transformation {
    name      = "ApiErrorLogLines"
    namespace = var.metric_namespace
    value     = "1"
    unit      = "Count"
  }
}

resource "aws_cloudwatch_metric_alarm" "api_error_logs" {
  alarm_name          = "${var.name_prefix}-api-error-logs"
  alarm_description   = "API logged more than ${var.api_error_log_threshold} ERROR lines in 5 minutes. Runbook: docs/runbooks/observability.md#api-or-scrape-unavailable"
  namespace           = var.metric_namespace
  metric_name         = "ApiErrorLogLines"
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = var.api_error_log_threshold
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alarms.arn]
  ok_actions          = [aws_sns_topic.alarms.arn]

  depends_on = [aws_cloudwatch_log_metric_filter.api_errors]
}

# ---------------------------------------------------------------------------
# Dashboard (one dashboard fits the free tier of three)
# ---------------------------------------------------------------------------

locals {
  queue_names = [
    var.game_events_queue_name,
    var.game_events_dlq_name,
    var.alert_deliveries_queue_name,
    var.alert_deliveries_dlq_name,
  ]

  alb_dimensions = ["LoadBalancer", var.alb_arn_suffix]
  tg_dimensions  = ["LoadBalancer", var.alb_arn_suffix, "TargetGroup", var.target_group_arn_suffix]

  widget_defaults = {
    view   = "timeSeries"
    region = local.region
    period = 60
  }

  dashboard_widgets = [
    {
      type = "metric", x = 0, y = 0, width = 8, height = 6
      properties = merge(local.widget_defaults, {
        title   = "ALB requests and errors"
        stat    = "Sum"
        metrics = [concat(["AWS/ApplicationELB", "RequestCount"], local.alb_dimensions), concat(["AWS/ApplicationELB", "HTTPCode_Target_5XX_Count"], local.tg_dimensions), concat(["AWS/ApplicationELB", "HTTPCode_Target_4XX_Count"], local.tg_dimensions), concat(["AWS/ApplicationELB", "HTTPCode_ELB_5XX_Count"], local.alb_dimensions)]
      })
    },
    {
      type = "metric", x = 8, y = 0, width = 8, height = 6
      properties = merge(local.widget_defaults, {
        title   = "API target response time"
        metrics = [concat(["AWS/ApplicationELB", "TargetResponseTime"], local.tg_dimensions, [{ stat = "p50", label = "p50" }]), concat(["AWS/ApplicationELB", "TargetResponseTime"], local.tg_dimensions, [{ stat = "p95", label = "p95" }])]
      })
    },
    {
      type = "metric", x = 16, y = 0, width = 8, height = 6
      properties = merge(local.widget_defaults, {
        title   = "API targets"
        stat    = "Maximum"
        metrics = [concat(["AWS/ApplicationELB", "HealthyHostCount"], local.tg_dimensions), concat(["AWS/ApplicationELB", "UnHealthyHostCount"], local.tg_dimensions)]
      })
    },
    {
      type = "metric", x = 0, y = 6, width = 12, height = 6
      properties = merge(local.widget_defaults, {
        title   = "SQS visible messages"
        stat    = "Maximum"
        metrics = [for name in local.queue_names : ["AWS/SQS", "ApproximateNumberOfMessagesVisible", "QueueName", name]]
      })
    },
    {
      type = "metric", x = 12, y = 6, width = 12, height = 6
      properties = merge(local.widget_defaults, {
        title   = "SQS oldest message age (seconds)"
        stat    = "Maximum"
        metrics = [for name in local.queue_names : ["AWS/SQS", "ApproximateAgeOfOldestMessage", "QueueName", name]]
      })
    },
    {
      type = "metric", x = 0, y = 12, width = 8, height = 6
      properties = merge(local.widget_defaults, {
        title   = "RDS CPU (%)"
        stat    = "Average"
        metrics = [["AWS/RDS", "CPUUtilization", "DBInstanceIdentifier", var.db_instance_identifier]]
      })
    },
    {
      type = "metric", x = 8, y = 12, width = 8, height = 6
      properties = merge(local.widget_defaults, {
        title   = "RDS connections"
        stat    = "Maximum"
        metrics = [["AWS/RDS", "DatabaseConnections", "DBInstanceIdentifier", var.db_instance_identifier]]
      })
    },
    {
      type = "metric", x = 16, y = 12, width = 8, height = 6
      properties = merge(local.widget_defaults, {
        title   = "RDS free storage (bytes)"
        stat    = "Minimum"
        metrics = [["AWS/RDS", "FreeStorageSpace", "DBInstanceIdentifier", var.db_instance_identifier]]
      })
    },
    {
      type = "metric", x = 0, y = 18, width = 12, height = 6
      properties = merge(local.widget_defaults, {
        title   = "ECS CPU by service (%)"
        stat    = "Average"
        metrics = [for short, service in var.service_names : ["AWS/ECS", "CPUUtilization", "ClusterName", var.cluster_name, "ServiceName", service, { label = short }]]
      })
    },
    {
      type = "metric", x = 12, y = 18, width = 12, height = 6
      properties = merge(local.widget_defaults, {
        title   = "ECS memory by service (%)"
        stat    = "Average"
        metrics = [for short, service in var.service_names : ["AWS/ECS", "MemoryUtilization", "ClusterName", var.cluster_name, "ServiceName", service, { label = short }]]
      })
    },
    {
      type = "metric", x = 0, y = 24, width = 24, height = 4
      properties = merge(local.widget_defaults, {
        title   = "API ERROR log lines"
        stat    = "Sum"
        period  = 300
        metrics = [[var.metric_namespace, "ApiErrorLogLines"]]
      })
    },
  ]
}

resource "aws_cloudwatch_dashboard" "this" {
  dashboard_name = var.name_prefix
  dashboard_body = jsonencode({ widgets = local.dashboard_widgets })
}
