# Staging secret rotation

Staging has two application secrets, both in Secrets Manager and injected by ECS only when a task
starts: the RDS-managed master credential and the BALLDONTLIE provider key. Terraform state holds
their ARNs, never their values. Rotating either one therefore needs a new task, not a new image.
Never paste a secret into a terminal command line, chat, ticket, or log.

## BALLDONTLIE provider key

1. Create a new key in the BALLDONTLIE account. Keep the old one active for now.
2. Store it without echoing it:

   ```bash
   AWS_REGION=us-east-1 scripts/aws/set-provider-key.sh
   ```

   The script reads the key from a hidden prompt or stdin and passes it to the CLI through stdin,
   so it never appears in the process list or shell history.
3. Restart the ingestor so it reads the new value (skip if it is scaled to zero):

   ```bash
   aws ecs update-service --cluster "$ECS_CLUSTER" --service courtpulse-staging-ingestor --force-new-deployment
   aws ecs wait services-stable --cluster "$ECS_CLUSTER" --services courtpulse-staging-ingestor
   ```

4. Check `/ecs/courtpulse-staging/ingestor` for successful polls and no 401/403 responses, and the
   stale-feed signals in [the observability runbook](observability.md#stale-feed).
5. Revoke the old key at BALLDONTLIE. Review ingestor logs for the old key's prefix in case it ever
   leaked into output; the application must not log it.

If the key tier changes, update `provider_requests_per_minute` (free 5, ALL-STAR 60, GOAT 600 per
minute) and apply.

## RDS master credential

RDS owns this secret (`manage_master_user_password = true`) and rotates it automatically; the
default schedule is every seven days. PostgreSQL keeps existing sessions, but new connections with
the old password fail. Tasks therefore degrade as their connection pools recycle: the API fails
readiness and workers stop heartbeating, and ECS replaces them with tasks that read the new value.
That self-heals but can cause several minutes of errors, so rotate deliberately:

```bash
secret_arn="$(terraform -chdir=infra/terraform/environments/staging output -raw database_master_secret_arn)"
aws secretsmanager rotate-secret --secret-id "$secret_arn"
aws secretsmanager describe-secret --secret-id "$secret_arn" --query 'LastRotatedDate'
for service in $ECS_SERVICES; do
  aws ecs update-service --cluster "$ECS_CLUSTER" --service "$service" --force-new-deployment \
    --query 'service.serviceName' --output text
done
aws ecs wait services-stable --cluster "$ECS_CLUSTER" --services $ECS_SERVICES
scripts/aws/smoke-test.sh "$CLOUDFRONT_URL"
```

`--force-new-deployment` keeps the same task definition; only the injected secret changes, which
demonstrates the engineering-plan requirement that a rotated secret is read without rebuilding
the image. To change the automatic schedule without rotating now:

```bash
aws secretsmanager rotate-secret --secret-id "$secret_arn" \
  --rotation-rules '{"ScheduleExpression":"rate(30 days)"}' --no-rotate-immediately
```

After an automatic rotation you did not initiate (alarms for unhealthy hosts or rising
connections errors shortly after `LastRotatedDate`), run the force-deployment loop above.

## CloudFront origin-verify header

The ALB forwards only requests carrying `X-CourtPulse-Origin-Verify`, whose value is a Terraform
`random_password` stored in the encrypted state bucket. Rotate it if state was exposed:

```bash
terraform -chdir=infra/terraform/environments/staging apply -replace=module.edge.random_password.origin_verify
```

The ALB rule changes in seconds while CloudFront propagates for several minutes, so API calls
through CloudFront can receive 403 briefly. That is acceptable for staging; production should
accept both values during a staged rollout.

## Other credentials

- Cognito: the SPA client has no secret and Cognito manages token-signing keys. To end a user's
  sessions: `aws cognito-idp admin-user-global-sign-out --user-pool-id <pool> --username <email>`.
- GitHub Actions: there are no stored AWS keys. To cut CI off immediately, delete the repository
  variable `AWS_DEPLOY_ROLE_ARN` (every job skips) or remove the role's inline policy.
- Local development values in `.env` are unrelated to AWS and must never be reused there.
