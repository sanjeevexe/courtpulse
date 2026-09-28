# Edge: one CloudFront distribution in front of a private S3 bucket (SPA) and
# an internet-facing ALB (REST + WebSocket). The default *.cloudfront.net
# certificate provides viewer HTTPS without a custom domain, which Cognito
# requires for callback URLs.
#
#   viewer --HTTPS--> CloudFront --OAC/SigV4--> S3 (index.html, /assets/*)
#                               \--HTTP:80 or HTTPS:443 + secret header--> ALB --> API tasks
#
# Without a custom domain there is no certificate CloudFront can validate for
# the ALB, so the CloudFront-to-ALB hop is plain HTTP. Bearer tokens cross that
# hop unencrypted; docs/adr/0012 records why this is accepted only for staging
# and how alb_certificate_arn removes it.

data "aws_caller_identity" "current" {}

data "aws_cloudfront_cache_policy" "caching_optimized" {
  name = "Managed-CachingOptimized"
}

data "aws_cloudfront_cache_policy" "caching_disabled" {
  name = "Managed-CachingDisabled"
}

# AllViewerExceptHostHeader forwards Authorization, Origin, query strings,
# cookies, and the WebSocket upgrade headers, but lets CloudFront send the
# origin's own Host. That works for both the HTTP origin and an HTTPS origin
# (whose certificate must match the origin name, not *.cloudfront.net), and the
# API never trusts a viewer-supplied Host. See docs/adr/0012.
data "aws_cloudfront_origin_request_policy" "all_viewer_except_host" {
  name = "Managed-AllViewerExceptHostHeader"
}

locals {
  https_origin  = var.alb_certificate_arn != null
  s3_origin_id  = "web-bucket"
  alb_origin_id = "api-alb"
  listener_port = local.https_origin ? 443 : 80
}

resource "random_password" "origin_verify" {
  length  = 48
  special = false
  # ALB header comparisons are case-insensitive, so mixed case adds nothing.
  upper = false
}

# ---------------------------------------------------------------------------
# Private web bucket
# ---------------------------------------------------------------------------

#trivy:ignore:AWS-0089 The bucket holds reproducible public build output; CloudFront is the only reader.
#trivy:ignore:AWS-0132 SSE-S3 avoids a KMS key charge and the extra CloudFront key-policy wiring SSE-KMS would need.
resource "aws_s3_bucket" "web" {
  bucket        = var.web_bucket_name
  force_destroy = var.web_bucket_force_destroy
}

resource "aws_s3_bucket_ownership_controls" "web" {
  bucket = aws_s3_bucket.web.id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_public_access_block" "web" {
  bucket = aws_s3_bucket.web.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_versioning" "web" {
  bucket = aws_s3_bucket.web.id

  versioning_configuration {
    status = "Enabled"
  }
}

#trivy:ignore:AWS-0132 SSE-S3 is deliberate; see the bucket resource.
resource "aws_s3_bucket_server_side_encryption_configuration" "web" {
  bucket = aws_s3_bucket.web.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "web" {
  bucket = aws_s3_bucket.web.id

  rule {
    id     = "expire-replaced-builds"
    status = "Enabled"

    filter {}

    noncurrent_version_expiration {
      noncurrent_days = 30
    }

    abort_incomplete_multipart_upload {
      days_after_initiation = 1
    }
  }

  depends_on = [aws_s3_bucket_versioning.web]
}

data "aws_iam_policy_document" "web_bucket" {
  statement {
    sid       = "AllowCloudFrontOriginAccessControl"
    actions   = ["s3:GetObject"]
    resources = ["${aws_s3_bucket.web.arn}/*"]

    principals {
      type        = "Service"
      identifiers = ["cloudfront.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "AWS:SourceArn"
      values   = [aws_cloudfront_distribution.this.arn]
    }
  }

  statement {
    sid     = "DenyInsecureTransport"
    effect  = "Deny"
    actions = ["s3:*"]
    resources = [
      aws_s3_bucket.web.arn,
      "${aws_s3_bucket.web.arn}/*",
    ]

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

resource "aws_s3_bucket_policy" "web" {
  bucket = aws_s3_bucket.web.id
  policy = data.aws_iam_policy_document.web_bucket.json

  depends_on = [aws_s3_bucket_public_access_block.web]
}

resource "aws_cloudfront_origin_access_control" "web" {
  name                              = "${var.name_prefix}-web"
  description                       = "SigV4 access from CloudFront to the private SPA bucket"
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

# ---------------------------------------------------------------------------
# Optional ALB access log bucket (off by default: storage and request cost)
# ---------------------------------------------------------------------------

data "aws_elb_service_account" "this" {
  count = var.enable_alb_access_logs ? 1 : 0
}

#trivy:ignore:AWS-0089 This is the log destination; logging it to another bucket would recurse.
#trivy:ignore:AWS-0090 Access logs are append-only and short-lived; versioning would only add cost.
#trivy:ignore:AWS-0132 ALB access logging supports only SSE-S3 destinations.
resource "aws_s3_bucket" "alb_logs" {
  count = var.enable_alb_access_logs ? 1 : 0

  bucket        = "${var.web_bucket_name}-alb-logs"
  force_destroy = true
}

resource "aws_s3_bucket_ownership_controls" "alb_logs" {
  count = var.enable_alb_access_logs ? 1 : 0

  bucket = aws_s3_bucket.alb_logs[0].id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_public_access_block" "alb_logs" {
  count = var.enable_alb_access_logs ? 1 : 0

  bucket = aws_s3_bucket.alb_logs[0].id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

#trivy:ignore:AWS-0132 ALB access logging supports only SSE-S3 destinations.
resource "aws_s3_bucket_server_side_encryption_configuration" "alb_logs" {
  count = var.enable_alb_access_logs ? 1 : 0

  bucket = aws_s3_bucket.alb_logs[0].id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "alb_logs" {
  count = var.enable_alb_access_logs ? 1 : 0

  bucket = aws_s3_bucket.alb_logs[0].id

  rule {
    id     = "expire-access-logs"
    status = "Enabled"

    filter {}

    expiration {
      days = var.alb_access_log_retention_days
    }

    abort_incomplete_multipart_upload {
      days_after_initiation = 1
    }
  }
}

data "aws_iam_policy_document" "alb_logs" {
  count = var.enable_alb_access_logs ? 1 : 0

  # Regions launched before August 2022 authorize the regional ELB account;
  # newer regions authorize the log delivery service principal.
  statement {
    sid       = "AllowElbLogDelivery"
    actions   = ["s3:PutObject"]
    resources = ["${aws_s3_bucket.alb_logs[0].arn}/alb/AWSLogs/${data.aws_caller_identity.current.account_id}/*"]

    principals {
      type        = "AWS"
      identifiers = [data.aws_elb_service_account.this[0].arn]
    }

    principals {
      type        = "Service"
      identifiers = ["logdelivery.elasticloadbalancing.amazonaws.com"]
    }
  }

  statement {
    sid     = "DenyInsecureTransport"
    effect  = "Deny"
    actions = ["s3:*"]
    resources = [
      aws_s3_bucket.alb_logs[0].arn,
      "${aws_s3_bucket.alb_logs[0].arn}/*",
    ]

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

resource "aws_s3_bucket_policy" "alb_logs" {
  count = var.enable_alb_access_logs ? 1 : 0

  bucket = aws_s3_bucket.alb_logs[0].id
  policy = data.aws_iam_policy_document.alb_logs[0].json

  depends_on = [aws_s3_bucket_public_access_block.alb_logs]
}

# ---------------------------------------------------------------------------
# Application Load Balancer
# ---------------------------------------------------------------------------

# The ALB must be internet-facing for a CloudFront custom origin. Its security
# group admits only the CloudFront origin-facing prefix list, and the listener
# forwards only requests carrying the secret origin-verify header.
#trivy:ignore:AWS-0053 CloudFront custom origins require a public ALB; ingress is limited to CloudFront addresses plus a secret header.
resource "aws_lb" "this" {
  name               = var.name_prefix
  load_balancer_type = "application"
  internal           = false
  security_groups    = [var.alb_security_group_id]
  subnets            = var.public_subnet_ids
  ip_address_type    = "ipv4"

  idle_timeout               = var.alb_idle_timeout_seconds
  drop_invalid_header_fields = true
  desync_mitigation_mode     = "defensive"
  enable_deletion_protection = false
  enable_http2               = true

  dynamic "access_logs" {
    for_each = var.enable_alb_access_logs ? [1] : []

    content {
      bucket  = aws_s3_bucket.alb_logs[0].id
      prefix  = "alb"
      enabled = true
    }
  }

  depends_on = [aws_s3_bucket_policy.alb_logs]

  lifecycle {
    precondition {
      condition     = length(var.name_prefix) <= 28
      error_message = "name_prefix must be at most 28 characters so ALB and target group names fit AWS limits."
    }
  }
}

resource "aws_lb_target_group" "api" {
  name                 = "${var.name_prefix}-api"
  port                 = var.api_container_port
  protocol             = "HTTP"
  target_type          = "ip"
  vpc_id               = var.vpc_id
  deregistration_delay = var.deregistration_delay_seconds

  health_check {
    enabled             = true
    path                = var.api_health_check_path
    protocol            = "HTTP"
    port                = "traffic-port"
    matcher             = "200"
    interval            = 15
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }
}

#trivy:ignore:AWS-0054 Without a custom domain the CloudFront-to-ALB hop is HTTP; supply alb_certificate_arn for an HTTPS listener (ADR 0012).
resource "aws_lb_listener" "origin" {
  load_balancer_arn = aws_lb.this.arn
  port              = local.listener_port
  protocol          = local.https_origin ? "HTTPS" : "HTTP"
  ssl_policy        = local.https_origin ? "ELBSecurityPolicy-TLS13-1-2-2021-06" : null
  certificate_arn   = var.alb_certificate_arn

  # Anything that did not come through CloudFront with the secret header,
  # or is outside the API paths, is refused.
  default_action {
    type = "fixed-response"

    fixed_response {
      content_type = "text/plain"
      message_body = "Forbidden"
      status_code  = "403"
    }
  }

  lifecycle {
    precondition {
      condition     = !local.https_origin || var.alb_origin_domain_name != null
      error_message = "alb_origin_domain_name is required with alb_certificate_arn: CloudFront validates the origin certificate against that name."
    }
  }
}

resource "aws_lb_listener_rule" "api_from_cloudfront" {
  listener_arn = aws_lb_listener.origin.arn
  priority     = 10

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.api.arn
  }

  condition {
    http_header {
      http_header_name = var.origin_verify_header_name
      values           = [random_password.origin_verify.result]
    }
  }

  condition {
    path_pattern {
      values = var.api_path_patterns
    }
  }
}

# ---------------------------------------------------------------------------
# CloudFront
# ---------------------------------------------------------------------------

resource "aws_cloudfront_function" "spa_routing" {
  name    = "${var.name_prefix}-spa-routing"
  runtime = "cloudfront-js-2.0"
  comment = "Rewrite extension-less SPA routes to /index.html"
  publish = true
  code    = file("${path.module}/spa-routing.js")
}

# The SPA shell must revalidate on every request (index.html is uploaded with
# Cache-Control: no-cache); min_ttl 0 lets CloudFront honor that.
resource "aws_cloudfront_cache_policy" "spa_shell" {
  name        = "${var.name_prefix}-spa-shell"
  comment     = "Honor origin Cache-Control; nothing cached without it"
  min_ttl     = 0
  default_ttl = 0
  max_ttl     = 31536000

  parameters_in_cache_key_and_forwarded_to_origin {
    enable_accept_encoding_brotli = true
    enable_accept_encoding_gzip   = true

    cookies_config {
      cookie_behavior = "none"
    }

    headers_config {
      header_behavior = "none"
    }

    query_strings_config {
      query_string_behavior = "none"
    }
  }
}

# Equivalent to apps/web/nginx.conf, applied to SPA and API responses alike.
resource "aws_cloudfront_response_headers_policy" "security" {
  name    = "${var.name_prefix}-security-headers"
  comment = "CSP, HSTS, nosniff, frame denial, and referrer policy"

  security_headers_config {
    content_security_policy {
      content_security_policy = var.content_security_policy
      override                = true
    }

    strict_transport_security {
      access_control_max_age_sec = 31536000
      include_subdomains         = false
      preload                    = false
      override                   = true
    }

    content_type_options {
      override = true
    }

    frame_options {
      frame_option = "DENY"
      override     = true
    }

    referrer_policy {
      referrer_policy = "strict-origin-when-cross-origin"
      override        = true
    }

    # "X-XSS-Protection: 0" disables the legacy auditor, as current guidance recommends.
    xss_protection {
      protection = false
      override   = true
    }
  }

  custom_headers_config {
    items {
      header   = "Permissions-Policy"
      value    = "camera=(), microphone=(), geolocation=(), payment=()"
      override = true
    }
  }
}

# The default *.cloudfront.net certificate uses CloudFront's default TLS policy;
# a custom-domain certificate would allow minimum_protocol_version TLSv1.2_2021.
#trivy:ignore:AWS-0010 Standard logging is off for cost; CloudWatch distribution metrics remain available.
#trivy:ignore:AWS-0011 AWS WAF (about $5/month per web ACL plus rules) is out of budget for staging; ADR 0012 lists it for production.
resource "aws_cloudfront_distribution" "this" {
  enabled             = true
  is_ipv6_enabled     = true
  http_version        = "http2and3"
  comment             = "${var.name_prefix} SPA and API"
  price_class         = var.cloudfront_price_class
  default_root_object = "index.html"
  wait_for_deployment = true

  origin {
    origin_id                = local.s3_origin_id
    domain_name              = aws_s3_bucket.web.bucket_regional_domain_name
    origin_access_control_id = aws_cloudfront_origin_access_control.web.id
  }

  origin {
    origin_id   = local.alb_origin_id
    domain_name = local.https_origin ? var.alb_origin_domain_name : aws_lb.this.dns_name

    custom_origin_config {
      http_port                = 80
      https_port               = 443
      origin_protocol_policy   = local.https_origin ? "https-only" : "http-only"
      origin_ssl_protocols     = ["TLSv1.2"]
      origin_read_timeout      = var.origin_read_timeout_seconds
      origin_keepalive_timeout = 60
    }

    custom_header {
      name  = var.origin_verify_header_name
      value = random_password.origin_verify.result
    }
  }

  # SPA shell and deep links.
  default_cache_behavior {
    target_origin_id           = local.s3_origin_id
    viewer_protocol_policy     = "redirect-to-https"
    allowed_methods            = ["GET", "HEAD"]
    cached_methods             = ["GET", "HEAD"]
    compress                   = true
    cache_policy_id            = aws_cloudfront_cache_policy.spa_shell.id
    response_headers_policy_id = aws_cloudfront_response_headers_policy.security.id

    function_association {
      event_type   = "viewer-request"
      function_arn = aws_cloudfront_function.spa_routing.arn
    }
  }

  # Content-hashed Vite output, uploaded with a one-year immutable Cache-Control.
  ordered_cache_behavior {
    path_pattern               = "/assets/*"
    target_origin_id           = local.s3_origin_id
    viewer_protocol_policy     = "redirect-to-https"
    allowed_methods            = ["GET", "HEAD"]
    cached_methods             = ["GET", "HEAD"]
    compress                   = true
    cache_policy_id            = data.aws_cloudfront_cache_policy.caching_optimized.id
    response_headers_policy_id = aws_cloudfront_response_headers_policy.security.id
  }

  # REST, WebSocket, and OpenAPI: never cached, every method allowed.
  dynamic "ordered_cache_behavior" {
    for_each = var.api_path_patterns

    content {
      path_pattern               = ordered_cache_behavior.value
      target_origin_id           = local.alb_origin_id
      viewer_protocol_policy     = "https-only"
      allowed_methods            = ["GET", "HEAD", "OPTIONS", "PUT", "POST", "PATCH", "DELETE"]
      cached_methods             = ["GET", "HEAD"]
      compress                   = false
      cache_policy_id            = data.aws_cloudfront_cache_policy.caching_disabled.id
      origin_request_policy_id   = data.aws_cloudfront_origin_request_policy.all_viewer_except_host.id
      response_headers_policy_id = aws_cloudfront_response_headers_policy.security.id
    }
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  viewer_certificate {
    cloudfront_default_certificate = true
  }
}
