# Container registries plus a GitHub Actions deploy role reachable only
# through OIDC from this repository's main branch or its staging environment.
# All ECS/S3/Logs ARNs are derived from names, so `terraform apply
# -target=module.cicd` can bootstrap registries and the role before any
# runtime resource exists (docs/deployment/aws-staging.md). The CloudFront
# invalidation grant lives in the environment root because the distribution
# ID is not known in advance.

data "aws_caller_identity" "current" {}

data "aws_region" "current" {}

locals {
  account_id = data.aws_caller_identity.current.account_id
  region     = data.aws_region.current.region

  repositories = {
    api    = "${var.project}-api"
    worker = "${var.project}-worker"
  }

  cluster_arn = "arn:aws:ecs:${local.region}:${local.account_id}:cluster/${var.cluster_name}"
  service_arns = [
    for name in var.service_names : "arn:aws:ecs:${local.region}:${local.account_id}:service/${var.cluster_name}/${name}"
  ]
  family_revision_arns = [
    for family in var.task_definition_families : "arn:aws:ecs:${local.region}:${local.account_id}:task-definition/${family}:*"
  ]
  oneoff_revision_arns = [
    for family in var.oneoff_task_families : "arn:aws:ecs:${local.region}:${local.account_id}:task-definition/${family}:*"
  ]
  cluster_task_arns = "arn:aws:ecs:${local.region}:${local.account_id}:task/${var.cluster_name}/*"
  web_bucket_arn    = "arn:aws:s3:::${var.web_bucket_name}"
  oneoff_log_arn    = "arn:aws:logs:${local.region}:${local.account_id}:log-group:${var.oneoff_log_group_name}"

  github_oidc_url          = "https://token.actions.githubusercontent.com"
  github_oidc_provider_arn = var.create_github_oidc_provider ? aws_iam_openid_connect_provider.github[0].arn : data.aws_iam_openid_connect_provider.github[0].arn
}

# ---------------------------------------------------------------------------
# ECR
# ---------------------------------------------------------------------------

#trivy:ignore:AWS-0033 AES-256 repository encryption avoids a customer-managed KMS key charge.
resource "aws_ecr_repository" "this" {
  for_each = local.repositories

  name                 = each.value
  image_tag_mutability = "IMMUTABLE"
  force_delete         = var.ecr_force_delete

  image_scanning_configuration {
    scan_on_push = true
  }

  encryption_configuration {
    encryption_type = "AES256"
  }
}

resource "aws_ecr_lifecycle_policy" "this" {
  for_each = aws_ecr_repository.this

  repository = each.value.name
  policy = jsonencode({
    rules = [
      {
        rulePriority = 1
        description  = "Expire untagged manifests after ${var.ecr_untagged_expiry_days} days"
        selection = {
          tagStatus   = "untagged"
          countType   = "sinceImagePushed"
          countUnit   = "days"
          countNumber = var.ecr_untagged_expiry_days
        }
        action = { type = "expire" }
      },
      {
        rulePriority = 2
        description  = "Keep the newest ${var.ecr_keep_tagged_images} tagged images"
        selection = {
          tagStatus      = "tagged"
          tagPatternList = ["*"]
          countType      = "imageCountMoreThan"
          countNumber    = var.ecr_keep_tagged_images
        }
        action = { type = "expire" }
      },
    ]
  })
}

# ---------------------------------------------------------------------------
# GitHub Actions OIDC
# ---------------------------------------------------------------------------

# AWS validates GitHub's OIDC certificate through its own trust store, so no
# thumbprint is pinned here.
resource "aws_iam_openid_connect_provider" "github" {
  count = var.create_github_oidc_provider ? 1 : 0

  url            = local.github_oidc_url
  client_id_list = ["sts.amazonaws.com"]
}

data "aws_iam_openid_connect_provider" "github" {
  count = var.create_github_oidc_provider ? 0 : 1

  url = local.github_oidc_url
}

data "aws_iam_policy_document" "deploy_assume" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [local.github_oidc_provider_arn]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }

    # Jobs that declare `environment: staging` present the environment
    # subject; jobs without an environment present the branch ref.
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values = [
        "repo:${var.github_repository}:environment:${var.github_environment}",
        "repo:${var.github_repository}:ref:refs/heads/main",
      ]
    }
  }
}

resource "aws_iam_role" "deploy" {
  name                 = "${var.name_prefix}-github-deploy"
  description          = "GitHub Actions deploy role for ${var.github_repository} (${var.github_environment})"
  assume_role_policy   = data.aws_iam_policy_document.deploy_assume.json
  max_session_duration = 3600
}

data "aws_iam_policy_document" "deploy" {
  # ecr:GetAuthorizationToken does not support resource-level permissions.
  statement {
    sid       = "EcrAuthorization"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  statement {
    sid = "EcrPushPull"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:BatchGetImage",
      "ecr:CompleteLayerUpload",
      "ecr:DescribeImages",
      "ecr:DescribeRepositories",
      "ecr:GetDownloadUrlForLayer",
      "ecr:InitiateLayerUpload",
      "ecr:ListImages",
      "ecr:PutImage",
      "ecr:UploadLayerPart",
    ]
    resources = [for repository in aws_ecr_repository.this : repository.arn]
  }

  # Register/Describe/ListTaskDefinitions do not support resource-level permissions.
  statement {
    sid = "TaskDefinitionRevisions"
    actions = [
      "ecs:DescribeTaskDefinition",
      "ecs:ListTaskDefinitions",
      "ecs:RegisterTaskDefinition",
    ]
    resources = ["*"]
  }

  statement {
    sid       = "ReadTaskDefinitionTags"
    actions   = ["ecs:ListTagsForResource"]
    resources = local.family_revision_arns
  }

  # Tags written at creation time (task-definition provenance and one-off tasks).
  statement {
    sid       = "TagOnCreate"
    actions   = ["ecs:TagResource"]
    resources = concat(local.family_revision_arns, [local.cluster_task_arns])

    condition {
      test     = "StringEquals"
      variable = "ecs:CreateAction"
      values   = ["RegisterTaskDefinition", "RunTask"]
    }
  }

  statement {
    sid       = "UpdateServices"
    actions   = ["ecs:DescribeServices", "ecs:UpdateService"]
    resources = local.service_arns
  }

  statement {
    sid       = "RunOneOffTasks"
    actions   = ["ecs:RunTask"]
    resources = local.oneoff_revision_arns

    condition {
      test     = "ArnEquals"
      variable = "ecs:cluster"
      values   = [local.cluster_arn]
    }
  }

  statement {
    sid       = "ObserveOneOffTasks"
    actions   = ["ecs:DescribeTasks", "ecs:StopTask"]
    resources = [local.cluster_task_arns]
  }

  statement {
    sid       = "PassOnlyThisStacksTaskRoles"
    actions   = ["iam:PassRole"]
    resources = var.passable_role_arns

    condition {
      test     = "StringEquals"
      variable = "iam:PassedToService"
      values   = ["ecs-tasks.amazonaws.com"]
    }
  }

  statement {
    sid       = "ListWebBucket"
    actions   = ["s3:ListBucket", "s3:GetBucketLocation"]
    resources = [local.web_bucket_arn]
  }

  statement {
    sid       = "SyncWebObjects"
    actions   = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"]
    resources = ["${local.web_bucket_arn}/*"]
  }

  statement {
    sid       = "ReadOneOffTaskLogs"
    actions   = ["logs:GetLogEvents"]
    resources = [local.oneoff_log_arn, "${local.oneoff_log_arn}:*"]
  }
}

resource "aws_iam_role_policy" "deploy" {
  name   = "deploy"
  role   = aws_iam_role.deploy.id
  policy = data.aws_iam_policy_document.deploy.json
}
