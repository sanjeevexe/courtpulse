# Amazon Cognito replaces the local Keycloak realm (ADR 0007). The SPA is a
# public client using Authorization Code + S256 PKCE; no client secret exists.
# The API validates Cognito *access* tokens: they carry client_id (not aud),
# token_use=access, and cognito:groups.

data "aws_region" "current" {}

resource "aws_cognito_user_pool" "this" {
  name = "${var.name_prefix}-users"

  # Lite: the lowest-cost feature plan (first 10,000 MAU free); no advanced
  # security add-on is enabled.
  user_pool_tier      = "LITE"
  deletion_protection = var.deletion_protection ? "ACTIVE" : "INACTIVE"

  username_attributes      = ["email"]
  auto_verified_attributes = ["email"]

  username_configuration {
    case_sensitive = false
  }

  admin_create_user_config {
    allow_admin_create_user_only = !var.allow_self_signup
  }

  password_policy {
    minimum_length                   = 14
    require_lowercase                = true
    require_uppercase                = true
    require_numbers                  = true
    require_symbols                  = true
    temporary_password_validity_days = 3
  }

  # TOTP only; SMS MFA would incur SNS charges.
  mfa_configuration = "OPTIONAL"

  software_token_mfa_configuration {
    enabled = true
  }

  account_recovery_setting {
    recovery_mechanism {
      name     = "verified_email"
      priority = 1
    }
  }

  user_attribute_update_settings {
    attributes_require_verification_before_update = ["email"]
  }

  # Cognito's built-in sender is free and limited to a small daily quota,
  # which is enough for staging invitations and password resets.
  email_configuration {
    email_sending_account = "COGNITO_DEFAULT"
  }
}

resource "aws_cognito_user_pool_domain" "this" {
  domain       = var.domain_prefix
  user_pool_id = aws_cognito_user_pool.this.id
}

resource "aws_cognito_user_group" "operations" {
  name         = var.operations_group_name
  user_pool_id = aws_cognito_user_pool.this.id
  description  = "CourtPulse operators: access to /api/v1/operations/** and metrics"
}

resource "aws_cognito_user_pool_client" "web" {
  name         = "${var.name_prefix}-web"
  user_pool_id = aws_cognito_user_pool.this.id

  generate_secret                      = false
  allowed_oauth_flows_user_pool_client = true
  allowed_oauth_flows                  = ["code"]
  allowed_oauth_scopes                 = ["openid", "email", "profile"]
  supported_identity_providers         = ["COGNITO"]

  callback_urls = concat(["${var.web_origin}/auth/callback"], var.extra_callback_urls)
  logout_urls   = concat(["${var.web_origin}/"], var.extra_logout_urls)

  # The hosted UI performs sign-in; the browser only needs refresh (and the
  # SPA deliberately does not persist refresh material).
  explicit_auth_flows = ["ALLOW_REFRESH_TOKEN_AUTH"]

  access_token_validity  = 60
  id_token_validity      = 60
  refresh_token_validity = 1

  token_validity_units {
    access_token  = "minutes"
    id_token      = "minutes"
    refresh_token = "days"
  }

  prevent_user_existence_errors = "ENABLED"
  enable_token_revocation       = true
}
