# Reusable Fargate process: task definition, optional service, log group, and
# per-process IAM roles.
#
# Deployment ownership: Terraform owns the task-definition *template*
# (environment, secrets, roles, sizes, health checks). CI owns the image. The
# deploy scripts copy the family's latest ACTIVE revision, swap the "app"
# image, and register a new revision; the service ignores task_definition
# drift so a later terraform apply never reverts a deployed image. Terraform
# configuration changes reach running tasks at the next deploy.

data "aws_caller_identity" "current" {}

data "aws_region" "current" {}

locals {
  family         = "${var.name_prefix}-${var.name}"
  container_name = "app"
  collector_name = "aws-otel-collector"
  account_id     = data.aws_caller_identity.current.account_id
  region         = data.aws_region.current.region

  log_group_name = var.log_group_name != null ? var.log_group_name : aws_cloudwatch_log_group.this[0].name
  log_group_arn  = "arn:aws:logs:${local.region}:${local.account_id}:log-group:${local.log_group_name}"

  tracing_enabled = var.tracing.enabled

  tracing_environment = local.tracing_enabled ? {
    OTEL_SERVICE_NAME           = "${var.tracing.service_namespace}-${var.name}"
    OTEL_RESOURCE_ATTRIBUTES    = "deployment.environment.name=${var.tracing.environment_name},service.namespace=${var.tracing.service_namespace}"
    OTEL_EXPORTER_OTLP_ENDPOINT = "http://localhost:4318"
    OTEL_EXPORTER_OTLP_PROTOCOL = "http/protobuf"
    OTEL_TRACES_EXPORTER        = "otlp"
    OTEL_METRICS_EXPORTER       = "none"
    OTEL_LOGS_EXPORTER          = "none"
    OTEL_TRACES_SAMPLER         = "parentbased_traceidratio"
    OTEL_TRACES_SAMPLER_ARG     = tostring(var.tracing.sampling_ratio)
    JAVA_TOOL_OPTIONS           = "-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -javaagent:/opt/otel/opentelemetry-javaagent.jar"
  } : {}

  environment = merge(var.environment, local.tracing_environment)

  # Minimal collector: OTLP on loopback only, batched to X-Ray. No metrics or
  # logs pipelines, so no CloudWatch EMF charges.
  collector_config = yamlencode({
    receivers = {
      otlp = {
        protocols = {
          grpc = { endpoint = "127.0.0.1:4317" }
          http = { endpoint = "127.0.0.1:4318" }
        }
      }
    }
    processors = {
      batch = { timeout = "2s", send_batch_size = 50 }
    }
    exporters = {
      awsxray = { region = local.region }
    }
    service = {
      pipelines = {
        traces = {
          receivers  = ["otlp"]
          processors = ["batch"]
          exporters  = ["awsxray"]
        }
      }
    }
  })

  log_configuration = {
    logDriver = "awslogs"
    options = {
      awslogs-group         = local.log_group_name
      awslogs-region        = local.region
      awslogs-stream-prefix = var.name
      # Never block the JVM on a CloudWatch Logs stall.
      mode            = "non-blocking"
      max-buffer-size = "25m"
    }
  }

  # merge() skips null arguments, so optional blocks use `condition ? {...} : null`.
  app_container = merge(
    {
      name                   = local.container_name
      image                  = var.image
      essential              = true
      readonlyRootFilesystem = var.readonly_root_filesystem
      stopTimeout            = var.stop_timeout_seconds
      environment            = [for key in sort(keys(local.environment)) : { name = key, value = local.environment[key] }]
      secrets                = [for key in sort(keys(var.secrets)) : { name = key, valueFrom = var.secrets[key] }]
      mountPoints            = [{ sourceVolume = "tmp", containerPath = "/tmp", readOnly = false }]
      linuxParameters        = { initProcessEnabled = true }
      logConfiguration       = local.log_configuration
      portMappings           = var.container_port == null ? [] : [{ containerPort = var.container_port, protocol = "tcp", name = "http" }]
    },
    var.command == null ? null : { command = var.command },
    var.health_check == null ? null : {
      healthCheck = {
        command     = var.health_check.command
        interval    = var.health_check.interval
        timeout     = var.health_check.timeout
        retries     = var.health_check.retries
        startPeriod = var.health_check.start_period
      }
    },
    # With a sidecar, give the JVM a hard limit so MaxRAMPercentage sizes the
    # heap from its own share rather than the whole task.
    local.tracing_enabled ? {
      memory    = var.memory - var.tracing.collector_memory
      dependsOn = [{ containerName = local.collector_name, condition = "START" }]
    } : null,
  )

  collector_container = {
    name        = local.collector_name
    image       = var.tracing.collector_image
    essential   = false
    memory      = var.tracing.collector_memory
    environment = [{ name = "AOT_CONFIG_CONTENT", value = local.collector_config }]
    logConfiguration = merge(local.log_configuration, {
      options = merge(local.log_configuration.options, { awslogs-stream-prefix = "${var.name}-otel" })
    })
  }

  # A filtered for-expression avoids conditional tuple-length type errors.
  container_definitions = [
    for container in [local.app_container, local.collector_container] : container
    if container.name == local.container_name || local.tracing_enabled
  ]
}

#trivy:ignore:AWS-0017 CloudWatch Logs default encryption is used; a customer-managed KMS key would add a monthly charge.
resource "aws_cloudwatch_log_group" "this" {
  count = var.log_group_name == null ? 1 : 0

  name              = "/ecs/${var.name_prefix}/${var.name}"
  retention_in_days = var.log_retention_days
}

# ---------------------------------------------------------------------------
# Execution role: used by the ECS agent to pull the image, write logs, and
# resolve var.secrets before the container starts.
# ---------------------------------------------------------------------------

data "aws_iam_policy_document" "ecs_tasks_assume" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }

    # Confused-deputy protection.
    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [local.account_id]
    }

    condition {
      test     = "ArnLike"
      variable = "aws:SourceArn"
      values   = ["arn:aws:ecs:${local.region}:${local.account_id}:*"]
    }
  }
}

resource "aws_iam_role" "execution" {
  name               = var.execution_role_name
  description        = "ECS execution role for ${local.family}"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
}

data "aws_iam_policy_document" "execution" {
  # ecr:GetAuthorizationToken does not support resource-level permissions.
  statement {
    sid       = "EcrAuthorization"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  statement {
    sid = "PullApplicationImage"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:BatchGetImage",
      "ecr:GetDownloadUrlForLayer",
    ]
    resources = [var.ecr_repository_arn]
  }

  statement {
    sid       = "WriteContainerLogs"
    actions   = ["logs:CreateLogStream", "logs:PutLogEvents"]
    resources = ["${local.log_group_arn}:*"]
  }

  dynamic "statement" {
    for_each = length(var.execution_secret_arns) > 0 ? [1] : []

    content {
      sid       = "InjectSecrets"
      actions   = ["secretsmanager:GetSecretValue"]
      resources = var.execution_secret_arns
    }
  }
}

resource "aws_iam_role_policy" "execution" {
  name   = "execution"
  role   = aws_iam_role.execution.id
  policy = data.aws_iam_policy_document.execution.json
}

# ---------------------------------------------------------------------------
# Task role: the credentials the application itself receives.
# ---------------------------------------------------------------------------

resource "aws_iam_role" "task" {
  name               = var.task_role_name
  description        = "Application task role for ${local.family}"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
}

resource "aws_iam_role_policy" "application" {
  for_each = var.task_role_policies

  name   = each.key
  role   = aws_iam_role.task.id
  policy = each.value
}

data "aws_iam_policy_document" "tracing" {
  # X-Ray segment and sampling APIs do not support resource-level permissions.
  statement {
    sid = "ExportTraces"
    actions = [
      "xray:PutTraceSegments",
      "xray:PutTelemetryRecords",
      "xray:GetSamplingRules",
      "xray:GetSamplingTargets",
    ]
    resources = ["*"]
  }
}

resource "aws_iam_role_policy" "tracing" {
  count = local.tracing_enabled ? 1 : 0

  name   = "tracing"
  role   = aws_iam_role.task.id
  policy = data.aws_iam_policy_document.tracing.json
}

data "aws_iam_policy_document" "execute_command" {
  # SSM Messages channel actions do not support resource-level permissions.
  statement {
    sid = "EcsExecChannels"
    actions = [
      "ssmmessages:CreateControlChannel",
      "ssmmessages:CreateDataChannel",
      "ssmmessages:OpenControlChannel",
      "ssmmessages:OpenDataChannel",
    ]
    resources = ["*"]
  }
}

resource "aws_iam_role_policy" "execute_command" {
  count = var.enable_execute_command ? 1 : 0

  name   = "execute-command"
  role   = aws_iam_role.task.id
  policy = data.aws_iam_policy_document.execute_command.json
}

# ---------------------------------------------------------------------------
# Task definition and service
# ---------------------------------------------------------------------------

resource "aws_ecs_task_definition" "this" {
  family                   = local.family
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = tostring(var.cpu)
  memory                   = tostring(var.memory)
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn
  container_definitions    = jsonencode(local.container_definitions)

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }

  # Writable scratch space on Fargate task storage; the root filesystem is read-only.
  volume {
    name = "tmp"
  }

  # Keep superseded revisions ACTIVE: CI-registered revisions are the rollback
  # targets, and task-definition revisions cost nothing.
  skip_destroy = true

  lifecycle {
    precondition {
      condition     = !local.tracing_enabled || (var.tracing.collector_image != "" && var.memory - var.tracing.collector_memory >= 256)
      error_message = "Tracing needs a pinned collector image and leaves at least 256 MiB for the application container."
    }
  }
}

resource "aws_ecs_service" "this" {
  count = var.create_service ? 1 : 0

  name             = local.family
  cluster          = var.cluster_arn
  task_definition  = aws_ecs_task_definition.this.arn
  desired_count    = var.desired_count
  platform_version = "LATEST"

  capacity_provider_strategy {
    capacity_provider = var.capacity_provider
    weight            = 1
    base              = 0
  }

  network_configuration {
    subnets          = var.subnet_ids
    security_groups  = var.security_group_ids
    assign_public_ip = var.assign_public_ip
  }

  deployment_minimum_healthy_percent = var.deployment_minimum_healthy_percent
  deployment_maximum_percent         = var.deployment_maximum_percent

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  dynamic "load_balancer" {
    for_each = var.load_balancer == null ? [] : [var.load_balancer]

    content {
      target_group_arn = load_balancer.value.target_group_arn
      container_name   = local.container_name
      container_port   = var.container_port
    }
  }

  health_check_grace_period_seconds = var.load_balancer == null ? null : var.load_balancer.health_check_grace_period_seconds

  enable_execute_command  = var.enable_execute_command
  enable_ecs_managed_tags = true
  propagate_tags          = "SERVICE"
  wait_for_steady_state   = false

  lifecycle {
    # CI registers new revisions for each deploy and rollback; see the header comment.
    ignore_changes = [task_definition]

    precondition {
      condition     = var.load_balancer == null || var.container_port != null
      error_message = "A load balancer attachment requires container_port."
    }
  }
}
