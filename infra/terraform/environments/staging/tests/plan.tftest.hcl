# Offline plan tests. mock_provider replaces the AWS and random providers
# entirely: no credentials are read and no AWS API is called. Run with:
#   terraform init -backend=false && terraform test
# (docs/deployment/aws-staging.md runs it inside a container with --network none).

mock_provider "aws" {
  mock_data "aws_caller_identity" {
    defaults = {
      account_id = "123456789012"
    }
  }

  mock_data "aws_region" {
    defaults = {
      region = "us-east-1"
    }
  }

  mock_data "aws_availability_zones" {
    defaults = {
      names = ["us-east-1a", "us-east-1b", "us-east-1c"]
    }
  }

  mock_data "aws_iam_policy_document" {
    defaults = {
      json = "{\"Version\":\"2012-10-17\",\"Statement\":[]}"
    }
  }

  mock_resource "aws_cloudfront_distribution" {
    defaults = {
      domain_name = "d111111abcdef8.cloudfront.net"
      arn         = "arn:aws:cloudfront::123456789012:distribution/E2EXAMPLE"
    }
  }
}

mock_provider "random" {}

override_resource {
  target          = random_string.cognito_domain_suffix
  override_during = plan
  values = {
    result = "12345678"
  }
}

variables {
  owner              = "test-owner"
  github_repository  = "example/CourtPulse"
  email_from_address = "alerts@example.com"
  budget_email       = "budget@example.com"
  image_tag          = "0123456789abcdef0123456789abcdef01234567"
}

run "defaults_plan" {
  command = plan

  assert {
    condition     = module.api.service_name == "courtpulse-staging-api"
    error_message = "API service name must follow <project>-<environment>-api."
  }

  assert {
    condition     = local.content_security_policy == "default-src 'self'; connect-src 'self' https://cognito-idp.us-east-1.amazonaws.com https://courtpulse-staging-12345678.auth.us-east-1.amazoncognito.com; img-src 'self' data:; style-src 'self'; script-src 'self'; font-src 'self'; base-uri 'self'; frame-ancestors 'none'"
    error_message = "CSP must match apps/web/nginx.conf plus the two Cognito origins."
  }

  assert {
    condition     = local.worker_environment["COURTPULSE_SQS_ENDPOINT"] == "aws" && local.worker_environment["COURTPULSE_GAME_EVENTS_QUEUE"] == "courtpulse-staging-game-events.fifo"
    error_message = "Workers must use the default SQS endpoint and prefixed FIFO queue names."
  }

  assert {
    condition     = local.api_environment["COURTPULSE_AUTH_AUDIENCE_CLAIM"] == "client_id" && local.api_environment["COURTPULSE_AUTH_REQUIRED_TOKEN_USE"] == "access" && local.api_environment["COURTPULSE_AUTH_OPERATIONS_AUTHORITY"] == "ROLE_courtpulse-ops"
    error_message = "API must validate Cognito access tokens by client_id and map the ops group."
  }

  assert {
    condition     = length(module.processor.container_definitions) == 1 && jsonencode(module.processor.container_definitions[0].command) == jsonencode(["--processor-daemon"])
    error_message = "Processor runs exactly one container with the --processor-daemon command."
  }

  assert {
    condition     = module.api.container_definitions[0].readonlyRootFilesystem == true
    error_message = "Containers run with a read-only root filesystem by default."
  }

  assert {
    condition     = !contains([for env in module.api.container_definitions[0].environment : env.name], "COURTPULSE_DB_PASSWORD")
    error_message = "The database password must be an ECS secret, never a plain environment variable."
  }

  assert {
    condition     = module.cost.nightly_shutdown_enabled == false
    error_message = "Nightly shutdown is off by default."
  }

  assert {
    condition = alltrue([
      jsonencode(module.processor.container_definitions[0].command) == jsonencode(["--processor-daemon"]),
      jsonencode(module.delivery.container_definitions[0].command) == jsonencode(["--delivery-daemon"]),
      jsonencode(module.reconciliation.container_definitions[0].command) == jsonencode(["--reconciliation-daemon"]),
      jsonencode(module.ingestor.container_definitions[0].command) == jsonencode(["--ingest-daemon"]),
      jsonencode(module.migrate.container_definitions[0].command) == jsonencode(["--migrate"]),
      jsonencode(module.canary.container_definitions[0].command) == jsonencode(["--canary"]),
    ])
    error_message = "Each worker-image process must run its contract command."
  }

  assert {
    condition     = jsonencode(module.ingestor.container_definitions[0].healthCheck.command) == jsonencode(["CMD-SHELL", "find /tmp/courtpulse-ingestor-worker.heartbeat -mmin -1 | grep -q heartbeat"])
    error_message = "Worker health checks must read the per-process heartbeat file."
  }

  assert {
    condition     = local.ingestor_environment["COURTPULSE_PROVIDER_REQUESTS_PER_MINUTE"] == "30" && local.ingestor_environment["COURTPULSE_PROVIDER_BASE_URL"] == "https://api.balldontlie.io" && local.ingestor_environment["COURTPULSE_PROVIDER"] == "balldontlie"
    error_message = "The ingestor needs provider, base URL, and request-rate settings."
  }

  assert {
    condition     = local.delivery_environment["COURTPULSE_EMAIL_PROVIDER"] == "ses" && local.delivery_environment["COURTPULSE_EMAIL_FROM"] == "alerts@example.com"
    error_message = "The delivery worker sends through SES from the configured address."
  }

  assert {
    condition     = module.api.service_name != null && module.migrate.service_name == null && module.canary.service_name == null
    error_message = "migrate and canary are task families without services."
  }
}

run "all_optional_features_plan" {
  command = plan

  variables {
    enable_tracing          = true
    enable_nightly_shutdown = true
    enable_alb_access_logs  = true
    enable_alarm_topic_cmk  = true
    enable_execute_command  = true
    alarm_email             = "alarms@example.com"
    ingestor_desired_count  = 1
    alb_certificate_arn     = "arn:aws:acm:us-east-1:123456789012:certificate/00000000-0000-0000-0000-000000000000"
    alb_origin_domain_name  = "origin.staging.example.com"
  }

  assert {
    condition     = length(module.delivery.container_definitions) == 2
    error_message = "Tracing adds the ADOT collector sidecar."
  }

  assert {
    condition     = strcontains(module.api.container_definitions[0].environment[index([for env in module.api.container_definitions[0].environment : env.name], "JAVA_TOOL_OPTIONS")].value, "-javaagent:/opt/otel/opentelemetry-javaagent.jar")
    error_message = "Tracing attaches the OpenTelemetry Java agent."
  }

  assert {
    condition     = output.api_origin_protocol == "https"
    error_message = "A certificate switches the CloudFront origin to HTTPS."
  }
}

run "api_scale_out_is_rejected" {
  command = plan

  variables {
    api_desired_count = 2
  }

  expect_failures = [var.api_desired_count]
}

run "bad_image_tag_is_rejected" {
  command = plan

  variables {
    image_tag = "latest"
  }

  expect_failures = [var.image_tag]
}

run "missing_image_tag_blocks_full_apply" {
  command = plan

  variables {
    image_tag = null
  }

  expect_failures = [aws_ecs_cluster.this]
}
