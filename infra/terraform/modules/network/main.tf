# Two-AZ VPC without a NAT gateway.
#
#   public subnets  (2): internet-facing ALB and Fargate tasks with public IPs
#   private subnets (2): RDS only; their route table has no internet route
#
# Fargate tasks need outbound HTTPS to ECR, Secrets Manager, SQS, SES,
# CloudWatch Logs, Cognito JWKS, and the live data provider. A NAT gateway
# (~$32/month plus data processing) or interface endpoints (~$7/month each per
# AZ) would allow private task subnets; see docs/adr/0012 for the tradeoff.

data "aws_availability_zones" "available" {
  state = "available"

  filter {
    name   = "opt-in-status"
    values = ["opt-in-not-required"]
  }
}

data "aws_region" "current" {}

# CloudFront's origin-facing ranges. The list has a weight of roughly 55
# security-group rules, so the ALB group references it exactly once.
data "aws_ec2_managed_prefix_list" "cloudfront_origin_facing" {
  name = "com.amazonaws.global.cloudfront.origin-facing"
}

locals {
  azs = slice(data.aws_availability_zones.available.names, 0, 2)

  public_subnet_cidrs  = [for index in range(2) : cidrsubnet(var.vpc_cidr, 4, index)]
  private_subnet_cidrs = [for index in range(2) : cidrsubnet(var.vpc_cidr, 4, index + 8)]
}

# VPC flow logs are off for cost in this staging environment; enable them before handling sensitive production traffic.
resource "aws_vpc" "this" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = {
    Name = "${var.name_prefix}-vpc"
  }
}

# Remove every rule from the default security group so nothing can use it by accident.
resource "aws_default_security_group" "this" {
  vpc_id = aws_vpc.this.id

  tags = {
    Name = "${var.name_prefix}-default-deny"
  }
}

resource "aws_internet_gateway" "this" {
  vpc_id = aws_vpc.this.id

  tags = {
    Name = "${var.name_prefix}-igw"
  }
}

resource "aws_subnet" "public" {
  count = 2

  vpc_id            = aws_vpc.this.id
  availability_zone = local.azs[count.index]
  cidr_block        = local.public_subnet_cidrs[count.index]

  # Public IPs are requested explicitly per ECS service (assign_public_ip).
  map_public_ip_on_launch = false

  tags = {
    Name = "${var.name_prefix}-public-${local.azs[count.index]}"
    Tier = "public"
  }
}

resource "aws_subnet" "private" {
  count = 2

  vpc_id            = aws_vpc.this.id
  availability_zone = local.azs[count.index]
  cidr_block        = local.private_subnet_cidrs[count.index]

  map_public_ip_on_launch = false

  tags = {
    Name = "${var.name_prefix}-private-${local.azs[count.index]}"
    Tier = "private"
  }
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.this.id

  tags = {
    Name = "${var.name_prefix}-public"
  }
}

resource "aws_route" "public_internet" {
  route_table_id         = aws_route_table.public.id
  destination_cidr_block = "0.0.0.0/0"
  gateway_id             = aws_internet_gateway.this.id
}

resource "aws_route_table_association" "public" {
  count = 2

  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id
}

# Isolated: no default route. RDS needs no outbound internet access.
resource "aws_route_table" "private" {
  vpc_id = aws_vpc.this.id

  tags = {
    Name = "${var.name_prefix}-private"
  }
}

resource "aws_route_table_association" "private" {
  count = 2

  subnet_id      = aws_subnet.private[count.index].id
  route_table_id = aws_route_table.private.id
}

# Gateway endpoints are free. ECR image layers are served from S3, so pulls
# stay on the AWS network instead of the public S3 endpoint.
resource "aws_vpc_endpoint" "s3" {
  vpc_id            = aws_vpc.this.id
  service_name      = "com.amazonaws.${data.aws_region.current.region}.s3"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = [aws_route_table.public.id, aws_route_table.private.id]

  tags = {
    Name = "${var.name_prefix}-s3"
  }
}

# ---------------------------------------------------------------------------
# Security groups
#
#   CloudFront prefix list --(80|443)--> ALB --(8080)--> API tasks
#   API tasks, worker tasks --(5432)--> RDS
#   API tasks, worker tasks --(443)---> AWS APIs and the data provider
# Workers and one-off tasks have no ingress at all.
# ---------------------------------------------------------------------------

resource "aws_security_group" "alb" {
  name        = "${var.name_prefix}-alb"
  description = "CourtPulse ALB: ingress only from CloudFront origin-facing addresses"
  vpc_id      = aws_vpc.this.id

  tags = {
    Name = "${var.name_prefix}-alb"
  }

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_security_group" "api" {
  name        = "${var.name_prefix}-api"
  description = "CourtPulse API tasks: ingress only from the ALB"
  vpc_id      = aws_vpc.this.id

  tags = {
    Name = "${var.name_prefix}-api"
  }

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_security_group" "worker" {
  name        = "${var.name_prefix}-worker"
  description = "CourtPulse workers and one-off tasks: no ingress"
  vpc_id      = aws_vpc.this.id

  tags = {
    Name = "${var.name_prefix}-worker"
  }

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_security_group" "database" {
  name        = "${var.name_prefix}-database"
  description = "CourtPulse PostgreSQL: ingress only from API and worker tasks"
  vpc_id      = aws_vpc.this.id

  tags = {
    Name = "${var.name_prefix}-database"
  }

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_vpc_security_group_ingress_rule" "alb_from_cloudfront" {
  security_group_id = aws_security_group.alb.id
  description       = "CloudFront origin-facing addresses"
  ip_protocol       = "tcp"
  from_port         = var.alb_listener_port
  to_port           = var.alb_listener_port
  prefix_list_id    = data.aws_ec2_managed_prefix_list.cloudfront_origin_facing.id
}

resource "aws_vpc_security_group_egress_rule" "alb_to_api" {
  security_group_id            = aws_security_group.alb.id
  description                  = "Forward to API tasks and run target health checks"
  ip_protocol                  = "tcp"
  from_port                    = var.api_container_port
  to_port                      = var.api_container_port
  referenced_security_group_id = aws_security_group.api.id
}

resource "aws_vpc_security_group_ingress_rule" "api_from_alb" {
  security_group_id            = aws_security_group.api.id
  description                  = "HTTP and WebSocket traffic from the ALB"
  ip_protocol                  = "tcp"
  from_port                    = var.api_container_port
  to_port                      = var.api_container_port
  referenced_security_group_id = aws_security_group.alb.id
}

locals {
  task_security_groups = {
    api    = aws_security_group.api.id
    worker = aws_security_group.worker.id
  }
}

# Without NAT or interface endpoints, tasks reach AWS APIs over their public
# IPs. Egress is limited to HTTPS; DNS uses the VPC resolver, which security
# groups do not filter.
# Tasks need HTTPS to regional AWS APIs and the external data provider; there is no NAT or endpoint path in this cost-bounded design.
resource "aws_vpc_security_group_egress_rule" "tasks_https" {
  for_each = local.task_security_groups

  security_group_id = each.value
  description       = "HTTPS to AWS APIs, ECR, Cognito JWKS, and the data provider"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
  cidr_ipv4         = "0.0.0.0/0"
}

resource "aws_vpc_security_group_egress_rule" "tasks_database" {
  for_each = local.task_security_groups

  security_group_id            = each.value
  description                  = "PostgreSQL"
  ip_protocol                  = "tcp"
  from_port                    = var.database_port
  to_port                      = var.database_port
  referenced_security_group_id = aws_security_group.database.id
}

resource "aws_vpc_security_group_ingress_rule" "database_from_tasks" {
  for_each = local.task_security_groups

  security_group_id            = aws_security_group.database.id
  description                  = "PostgreSQL from ${each.key} tasks"
  ip_protocol                  = "tcp"
  from_port                    = var.database_port
  to_port                      = var.database_port
  referenced_security_group_id = each.value
}
