output "cloudfront_distribution_id" {
  description = "CloudFront distribution ID (for invalidations)."
  value       = aws_cloudfront_distribution.this.id
}

output "cloudfront_distribution_arn" {
  description = "CloudFront distribution ARN."
  value       = aws_cloudfront_distribution.this.arn
}

output "cloudfront_domain_name" {
  description = "Distribution domain name, for example d111111abcdef8.cloudfront.net."
  value       = aws_cloudfront_distribution.this.domain_name
}

output "web_bucket_name" {
  description = "Private S3 bucket holding the SPA build."
  value       = aws_s3_bucket.web.bucket
}

output "web_bucket_arn" {
  description = "ARN of the SPA bucket."
  value       = aws_s3_bucket.web.arn
}

output "alb_dns_name" {
  description = "ALB DNS name (direct requests receive 403; use the CloudFront URL)."
  value       = aws_lb.this.dns_name
}

output "alb_arn_suffix" {
  description = "ALB ARN suffix for CloudWatch dimensions."
  value       = aws_lb.this.arn_suffix
}

output "api_target_group_arn" {
  description = "API target group ARN. Depends on the listener rule so ECS attaches only after routing exists."
  value       = aws_lb_target_group.api.arn
  depends_on  = [aws_lb_listener_rule.api_from_cloudfront]
}

output "api_target_group_arn_suffix" {
  description = "API target group ARN suffix for CloudWatch dimensions."
  value       = aws_lb_target_group.api.arn_suffix
}

output "origin_protocol" {
  description = "Protocol CloudFront uses toward the ALB (http or https)."
  value       = local.https_origin ? "https" : "http"
}
