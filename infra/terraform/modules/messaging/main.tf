# FIFO queues mirror localstack/init/ready.d/10-create-queues.sh. Deduplication
# IDs are explicit (the outbox deduplication key); content-based deduplication
# stays off. SSE-SQS encrypts at rest without KMS request charges.

locals {
  queues = {
    game_events = {
      name               = "${var.name_prefix}-game-events.fifo"
      dlq_name           = "${var.name_prefix}-game-events-dlq.fifo"
      visibility_timeout = var.game_events_visibility_timeout_seconds
    }
    alert_deliveries = {
      name               = "${var.name_prefix}-alert-deliveries.fifo"
      dlq_name           = "${var.name_prefix}-alert-deliveries-dlq.fifo"
      visibility_timeout = var.alert_deliveries_visibility_timeout_seconds
    }
  }
}

# SSE-SQS (service-managed keys) encrypts at rest without a customer-managed KMS key charge.
resource "aws_sqs_queue" "dlq" {
  for_each = local.queues

  name                        = each.value.dlq_name
  fifo_queue                  = true
  content_based_deduplication = false
  sqs_managed_sse_enabled     = true
  message_retention_seconds   = var.dlq_message_retention_seconds
  receive_wait_time_seconds   = var.receive_wait_time_seconds
  visibility_timeout_seconds  = each.value.visibility_timeout
}

# SSE-SQS (service-managed keys) encrypts at rest without a customer-managed KMS key charge.
resource "aws_sqs_queue" "source" {
  for_each = local.queues

  name                        = each.value.name
  fifo_queue                  = true
  content_based_deduplication = false
  sqs_managed_sse_enabled     = true
  message_retention_seconds   = var.message_retention_seconds
  receive_wait_time_seconds   = var.receive_wait_time_seconds
  visibility_timeout_seconds  = each.value.visibility_timeout

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.dlq[each.key].arn
    maxReceiveCount     = var.max_receive_count
  })
}

# Only the matching source queue may use each DLQ. A separate resource avoids
# the source <-> DLQ reference cycle.
resource "aws_sqs_queue_redrive_allow_policy" "dlq" {
  for_each = local.queues

  queue_url = aws_sqs_queue.dlq[each.key].id

  redrive_allow_policy = jsonencode({
    redrivePermission = "byQueue"
    sourceQueueArns   = [aws_sqs_queue.source[each.key].arn]
  })
}

locals {
  all_queues = merge(
    { for key, queue in aws_sqs_queue.source : key => queue },
    { for key, queue in aws_sqs_queue.dlq : "${key}_dlq" => queue },
  )
}

data "aws_iam_policy_document" "deny_insecure_transport" {
  for_each = local.all_queues

  statement {
    sid       = "DenyInsecureTransport"
    effect    = "Deny"
    actions   = ["sqs:*"]
    resources = [each.value.arn]

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

resource "aws_sqs_queue_policy" "this" {
  for_each = local.all_queues

  queue_url = each.value.id
  policy    = data.aws_iam_policy_document.deny_insecure_transport[each.key].json
}
