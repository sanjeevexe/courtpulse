output "user_pool_id" {
  description = "Cognito user pool ID."
  value       = aws_cognito_user_pool.this.id
}

output "user_pool_arn" {
  description = "Cognito user pool ARN."
  value       = aws_cognito_user_pool.this.arn
}

output "issuer_uri" {
  description = "OIDC issuer (COURTPULSE_AUTH_ISSUER_URI)."
  value       = "https://cognito-idp.${data.aws_region.current.region}.amazonaws.com/${aws_cognito_user_pool.this.id}"
}

output "client_id" {
  description = "Public app client ID (COURTPULSE_AUTH_CLIENT_ID and COURTPULSE_AUTH_AUDIENCE)."
  value       = aws_cognito_user_pool_client.web.id
}

output "domain" {
  description = "Hosted UI domain host name."
  value       = "${aws_cognito_user_pool_domain.this.domain}.auth.${data.aws_region.current.region}.amazoncognito.com"
}

output "end_session_endpoint" {
  description = "Cognito logout endpoint (COURTPULSE_AUTH_END_SESSION_ENDPOINT)."
  value       = "https://${aws_cognito_user_pool_domain.this.domain}.auth.${data.aws_region.current.region}.amazoncognito.com/logout"
}

output "hosted_ui_url" {
  description = "Hosted UI sign-in URL for manual verification."
  value       = "https://${aws_cognito_user_pool_domain.this.domain}.auth.${data.aws_region.current.region}.amazoncognito.com/login?client_id=${aws_cognito_user_pool_client.web.id}&response_type=code&scope=openid+email+profile&redirect_uri=${urlencode("${var.web_origin}/auth/callback")}"
}

output "operations_group_name" {
  description = "Name of the operations group."
  value       = aws_cognito_user_group.operations.name
}
