# AWS staging deployment

This guide creates, deploys, verifies, rolls back, and destroys the CourtPulse staging environment
described in [ADR 0012](../adr/0012-aws-staging-deployment.md). Nothing in this repository creates
AWS resources on its own: every charge starts with a `terraform apply` that you review and approve.
Read the cost section before the first apply.

```text
browser --HTTPS--> CloudFront --OAC--> private S3 bucket (SPA)
                      \--/api/*, /ws/*, /v3/api-docs* (+ secret header)--> ALB --> api task
                                                                                      |
 processor, delivery, reconciliation, ingestor tasks --SQS FIFO--+                    |
                           \--------------------------------------+--> RDS PostgreSQL (private)
 Cognito (hosted UI, PKCE)   Secrets Manager   SES   CloudWatch alarms -> SNS   AWS Budget
```

## What costs money

Estimates below are **estimates** for us-east-1 on **2026-09-28**, on-demand prices, 730 hours per
month, default variables, light staging traffic, and no Free Tier. Prices change; confirm them in the
[AWS Pricing Calculator](https://calculator.aws/) before applying. Your bill is the authority.

| Resource | What drives the charge | Always-on estimate (USD/month) | Free Tier notes |
| --- | --- | --- | --- |
| [Application Load Balancer](https://aws.amazon.com/elasticloadbalancing/pricing/) | $0.0225/hour plus LCU usage (idle staging uses a small fraction of one LCU) | ~$17-18 | Legacy 12-month tier (accounts before 2025-07-15) included 750 ALB hours |
| [Public IPv4 addresses](https://aws.amazon.com/vpc/pricing/) | $0.005 per address-hour. **Count: 2 for the ALB (one per AZ) + 1 per running task** = 2 + api 1 + processor 1 + delivery 1 + reconciliation 1 = **6** (7 with the ingestor). Rolling deploys and one-off tasks add a few minutes of extra addresses. | ~$21.90 (6 addresses) | The EC2 Free Tier IPv4 allowance does not cover ALB or Fargate addresses |
| [Fargate](https://aws.amazon.com/fargate/pricing/), API | 0.5 vCPU x $0.04048/h + 1 GB x $0.004445/h | ~$18.02 | None |
| Fargate, 3 workers | 0.25 vCPU + 0.5 GB each: $9.01 each on demand; Fargate Spot (default) is typically ~70% less and varies | ~$8 on Spot (~$27 on demand) | None |
| Fargate, ingestor (off by default) | Same size as a worker, plus one public IPv4 | $0 (~$3 Spot + $3.65 IPv4 when on) | None |
| [RDS PostgreSQL](https://aws.amazon.com/rds/postgresql/pricing/) | db.t4g.micro single-AZ $0.016/h (~$11.68); 20 GB gp3 $0.115/GB-month (~$2.30); backups up to the DB size and 7-day Performance Insights are free | ~$14 | Legacy 12-month tier: 750 micro hours + 20 GB storage + 20 GB backup |
| [CloudFront](https://aws.amazon.com/cloudfront/pricing/) + CloudFront Function | Requests, data out, function invocations, invalidation paths | $0 | Always free: 1 TB out, 10M requests, 2M function invocations, 1,000 invalidation paths per month |
| [Secrets Manager](https://aws.amazon.com/secrets-manager/pricing/) | $0.40 per secret-month (RDS-managed master secret + provider key) + $0.05 per 10,000 calls | ~$0.80 | Short free trial for new secrets only |
| [CloudWatch](https://aws.amazon.com/cloudwatch/pricing/) | 10 standard alarms, 1 dashboard (~30 metrics), 1 log-derived metric, log ingestion $0.50/GB and storage $0.03/GB-month at 14-day retention | ~$0-2 | Always free: 10 alarms, 3 dashboards, 10 custom metrics, 5 GB logs. Any alarm you add beyond the ten costs $0.10/month |
| [SQS](https://aws.amazon.com/sqs/pricing/) | FIFO requests $0.50/million. Two consumers long-polling every 2 s make ~2.6M receives/month, plus queue-depth observations | ~$1 | Always free: 1M requests/month |
| [SES](https://aws.amazon.com/ses/pricing/) | $0.10 per 1,000 emails; identity verification is free | ~$0 | Sandbox by default (see below) |
| [Cognito](https://aws.amazon.com/cognito/pricing/) Lite | Monthly active users | $0 | 10,000 MAU free on Lite |
| [ECR](https://aws.amazon.com/ecr/pricing/) | $0.10/GB-month; lifecycle keeps 20 tagged images per repository and expires untagged manifests after 7 days | ~$0.10-0.40 | Legacy 12-month tier: 500 MB |
| [S3](https://aws.amazon.com/s3/pricing/) (SPA + state bucket) | A few MB and a few thousand requests | <$0.10 | Legacy 12-month tier: 5 GB |
| [AWS Budgets](https://aws.amazon.com/aws-cost-management/aws-budgets/pricing/) | One cost budget without actions | $0 | Confirm on the pricing page |
| [EventBridge Scheduler](https://aws.amazon.com/eventbridge/pricing/) (optional shutdown) | 12 invocations/day | $0 | 14M invocations/month free |
| Data transfer | CloudFront-to-origin and in-region ECR pulls are free; internet egress beyond 100 GB/month is billed | $0 | 100 GB/month internet egress free across services |
| NAT gateway, WAF, Container Insights, KMS keys, X-Ray | Not created by default | $0 | X-Ray (optional tracing): 100,000 traces/month free, then $5 per million |

**Always-on totals (estimate):** about **$80/month** with Spot workers, about **$100/month** with
on-demand workers, or roughly $0.11-0.14 per hour. That is well above the default $25 budget.

- **Nightly shutdown** (`enable_nightly_shutdown = true`, weekdays 07:45-23:00) removes about half
  of the Fargate, task-address, and RDS-instance hours, but the ALB (~$17), its two addresses
  (~$7.30), RDS storage, and secrets keep billing: expect roughly $50-55/month.
- **Create, demonstrate, destroy** is the pattern that fits a student budget: a four-hour demo costs
  well under $1 plus the fixed daily share of secrets. `terraform apply` takes about 20 minutes
  (RDS and CloudFront dominate) and `scripts/aws/teardown.sh` removes everything except the state
  bucket and an optional final snapshot.
- **AWS Free Tier changed on 2025-07-15.** Accounts created before then may still have the 12-month
  allowances noted above (RDS micro hours can remove ~$14). Newer accounts get time-limited credits
  on a Free plan instead. Check [AWS Free Tier](https://aws.amazon.com/free/) and your Billing
  console; do not assume anything here is free for your account.
- A budget **alerts; it never stops spending**. Destroying the stack is the only hard stop.
- GitHub Actions minutes are free for public repositories; private repositories consume the
  account's included minutes (a deploy takes roughly 15-25 minutes of runner time across jobs).

## Prerequisites

- An AWS account you control, with MFA on the root user and an administrator identity for daily
  work (IAM Identity Center is recommended; do not use root access keys).
- [AWS CLI v2](https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html),
  signed in to that account (`aws sts get-caller-identity` shows the expected account).
- Terraform **1.10 or newer** (tested with 1.16.4; the S3 backend's `use_lockfile` needs 1.10).
- Docker Desktop (only for the optional local image build), `jq`, `git`, and the
  [GitHub CLI](https://cli.github.com/) for setting repository variables.
- The CourtPulse GitHub repository with Actions enabled. The deploy workflow runs after a workflow
  named **CI** succeeds on `main`; until such a workflow exists, run it manually.
- A mailbox for the SES sender, budget alerts, and alarms (plus-addressing is fine).

Validate the Terraform offline at any time (no credentials; Docker images are pinned in the command):

```bash
docker run --rm -v "$PWD":/w -w /w hashicorp/terraform:1.16.4 fmt -check -recursive infra/terraform
for root in bootstrap environments/staging; do
  docker run --rm -v "$PWD":/w -w "/w/infra/terraform/$root" hashicorp/terraform:1.16.4 init -backend=false -input=false
  docker run --rm -v "$PWD":/w -w "/w/infra/terraform/$root" hashicorp/terraform:1.16.4 validate
done
# Plans every module against mock providers with networking disabled.
docker run --rm --network none -v "$PWD":/w -w /w/infra/terraform/environments/staging \
  hashicorp/terraform:1.16.4 test
docker run --rm -v "$PWD":/w -w /w/infra/terraform --entrypoint tflint \
  ghcr.io/terraform-linters/tflint:latest --init --config=/w/infra/terraform/.tflint.hcl
docker run --rm -v "$PWD":/w -w /w/infra/terraform --entrypoint tflint \
  ghcr.io/terraform-linters/tflint:latest --recursive --config=/w/infra/terraform/.tflint.hcl
docker run --rm -v "$PWD":/w -w /w aquasec/trivy:latest config --skip-dirs '**/.terraform' infra/terraform
```

(TFLint plugins are downloaded into the container on each run; mount a cache directory at
`TFLINT_PLUGIN_DIR` to keep them.)

## 1. Create the state bucket

```bash
cp infra/terraform/bootstrap/terraform.tfvars.example infra/terraform/bootstrap/terraform.tfvars
$EDITOR infra/terraform/bootstrap/terraform.tfvars     # owner is required
scripts/aws/bootstrap-state.sh
```

The script shows your AWS identity, runs Terraform's interactive plan and approval for one S3
bucket, and writes the ignored `infra/terraform/environments/staging/backend.hcl`. Keep the
bootstrap directory's local `terraform.tfstate`; if it is lost, import the bucket instead of
recreating it.

## 2. Configure the environment

```bash
cd infra/terraform/environments/staging
cp terraform.tfvars.example terraform.tfvars
$EDITOR terraform.tfvars
terraform init -backend-config=backend.hcl
```

Set at least `owner`, `github_repository` (`owner/name` with GitHub's exact letter case; the IAM
trust policy compares it case-sensitively), `email_from_address`, `budget_email`, and optionally
`alarm_email`. Leave `image_tag` commented for now. If another stack in the account
already created the GitHub OIDC provider, set `create_github_oidc_provider = false`.

## 3. Registries, deploy role, and budget first

The full stack needs images that do not exist yet, so the first apply is deliberately narrow. It
creates only the two ECR repositories and their lifecycle policies, the GitHub OIDC provider and
deploy role, and the monthly budget, all of which are free until images are stored:

```bash
terraform apply -target=module.cicd -target=module.cost.aws_budgets_budget.monthly
```

Terraform warns that `-target` is for exceptional situations; this bootstrap is one. Confirm the
budget subscription email if AWS sends one.

## 4. Push the first images

Set the four variables the image job needs (repository-level; see step 8 for why):

```bash
gh variable set AWS_REGION --body "us-east-1"
gh variable set AWS_DEPLOY_ROLE_ARN --body "$(terraform output -raw deploy_role_arn)"
gh variable set ECR_API_REPOSITORY --body "$(terraform output -raw ecr_api_repository_url)"
gh variable set ECR_WORKER_REPOSITORY --body "$(terraform output -raw ecr_worker_repository_url)"
```

Run **Actions > Deploy staging > Run workflow** on `main` with **images_only** checked. It builds
`courtpulse-api` and `courtpulse-worker` for the current `main` commit and pushes them with
provenance and SBOM attestations. Alternatively, from a clean checkout of that commit:

```bash
export AWS_REGION=us-east-1
export ECR_API_REPOSITORY="$(terraform output -raw ecr_api_repository_url)"
export ECR_WORKER_REPOSITORY="$(terraform output -raw ecr_worker_repository_url)"
scripts/aws/build-push-images.sh "$(git rev-parse HEAD)"   # slow on Apple Silicon (amd64 emulation)
```

Put that full commit SHA in `terraform.tfvars` as `image_tag`.

## 5. Apply the environment

```bash
terraform plan -out=staging.tfplan
terraform apply staging.tfplan
```

Read the plan: 137 resources with the default variables (170 with every optional feature on), no
NAT gateway, one ALB, and one `db.t4g.micro`. The apply takes about 20 minutes. Afterwards:

- Click the SES verification link sent to `email_from_address`.
- Confirm the SNS subscription email for alarms.
- The API starts, runs Flyway under its advisory lock, and becomes healthy behind the ALB.

## 6. SES sandbox

New accounts are in the SES sandbox: you can send only **to verified addresses**, at most 200
messages per 24 hours and one per second. Verify each test recipient (free), or request production
access in the SES console when you need arbitrary recipients:

```bash
aws sesv2 create-email-identity --email-identity recipient@example.com
aws sesv2 get-email-identity --email-identity "$(terraform output -raw ses_from_identity)" \
  --query 'VerifiedForSendingStatus'
```

## 7. First Cognito user and the operations group

Self sign-up is off. Create a user (Cognito emails a temporary password from its built-in sender,
which is limited to a small daily quota) and grant the operations group if needed:

```bash
pool_id="$(terraform output -raw cognito_user_pool_id)"
aws cognito-idp admin-create-user --user-pool-id "$pool_id" --username you@example.com \
  --user-attributes Name=email,Value=you@example.com Name=email_verified,Value=true \
  --desired-delivery-mediums EMAIL
aws cognito-idp admin-add-user-to-group --user-pool-id "$pool_id" --username you@example.com \
  --group-name "$(terraform output -raw cognito_operations_group)"
```

Use test identities only: the CloudFront-to-ALB hop is HTTP until you add a domain certificate.

## 8. GitHub environment and variables

1. **Settings > Environments > New environment** named `staging`. Under deployment branches, allow
   only `main`. Add yourself as a required reviewer if you want to approve each deploy.
2. Set every workflow variable from Terraform outputs (review the printed commands first):

   ```bash
   scripts/aws/github-variables.sh            # prints gh variable set ... commands
   scripts/aws/github-variables.sh --apply    # sets them
   ```

Use **repository** variables. Each job's `if: vars.AWS_DEPLOY_ROLE_ARN != ''` guard is evaluated
before the job enters the `staging` environment, so an environment-only variable would make every
job skip. No GitHub secrets are needed: AWS access is OIDC-only and the deploy role trusts only
`repo:<owner>/<repo>:environment:staging` and `repo:<owner>/<repo>:ref:refs/heads/main`.

| Variable | Example | Terraform output |
| --- | --- | --- |
| `AWS_REGION` | `us-east-1` | `github_actions_variables` |
| `AWS_DEPLOY_ROLE_ARN` | `arn:aws:iam::123456789012:role/courtpulse-staging-github-deploy` | `deploy_role_arn` |
| `ECR_API_REPOSITORY` | `123456789012.dkr.ecr.us-east-1.amazonaws.com/courtpulse-api` | `ecr_api_repository_url` |
| `ECR_WORKER_REPOSITORY` | `123456789012.dkr.ecr.us-east-1.amazonaws.com/courtpulse-worker` | `ecr_worker_repository_url` |
| `ECS_CLUSTER` | `courtpulse-staging` | `ecs_cluster_name` |
| `ECS_SERVICES` | `courtpulse-staging-api courtpulse-staging-processor ...` (space-separated) | `ecs_service_names` |
| `MIGRATE_TASK_FAMILY` | `courtpulse-staging-migrate` | `migrate_task_family` |
| `CANARY_TASK_FAMILY` | `courtpulse-staging-canary` | `canary_task_family` |
| `ONEOFF_LOG_GROUP` | `/ecs/courtpulse-staging/one-off` | `oneoff_log_group_name` |
| `TASK_SUBNETS` | `subnet-aaa,subnet-bbb` (public subnets) | `public_subnet_ids` |
| `TASK_SECURITY_GROUP` | `sg-0123...` (worker group, no ingress) | `worker_security_group_id` |
| `WEB_BUCKET` | `courtpulse-staging-web-123456789012` | `web_bucket_name` |
| `CLOUDFRONT_DISTRIBUTION_ID` | `E2EXAMPLE` | `cloudfront_distribution_id` |
| `CLOUDFRONT_URL` | `https://d111111abcdef8.cloudfront.net` | `cloudfront_url` |

## 9. First deploy

Run **Actions > Deploy staging > Run workflow** on `main` (or push to `main` after CI exists). The
`deploy` job, in order:

1. `scripts/aws/run-oneoff-task.sh migrate <sha>` runs `--migrate` and fails the deploy on a
   non-zero exit, printing the task log.
2. `scripts/aws/deploy-services.sh <sha>` registers a revision per service (latest template, new
   image, provenance tags), updates each service, and waits until every primary deployment has
   completed on the new revision. A circuit-breaker rollback fails the job.
3. `scripts/aws/run-oneoff-task.sh canary <sha>` imports the synthetic fixture idempotently and
   waits for its known checksum, which proves processor, SQS, and PostgreSQL work end to end.
4. `scripts/aws/publish-web.sh` uploads hashed assets (immutable), then `index.html` (no-cache),
   then prunes removed assets; `scripts/aws/invalidate-web.sh` invalidates `/index.html`.
5. `scripts/aws/smoke-test.sh` checks the SPA and a deep link, security headers on HTML and API
   responses, `/api/v1/games` JSON, `/api/v1/auth/config` (`enabled=true`, Cognito issuer),
   `/api/v1/me` returning 401 anonymously, and the canary game.

The SPA is published after the canary so the browser never sees a build whose API is not deployed.
Every step is a script that you can run locally with the same variables exported.

## 10. Verify

- Open `terraform output -raw cloudfront_url`, browse games, sign in through the hosted UI, follow
  a game, and sign out. Operators should reach the operations pages.
- A game detail page should hold a WebSocket (`wss://<distribution>/ws/v1/games`) open; watch the
  browser's network panel. If the handshake returns 403, the API is not yet accepting the
  CloudFront origin (see ADR 0012 consequences).
- Direct requests to `terraform output -raw api_origin` return 403.
- CloudWatch dashboard `courtpulse-staging`: ALB, SQS, RDS, and ECS widgets have data; alarms are
  `OK` or `INSUFFICIENT_DATA`, not `ALARM`.
- Billing > Cost Explorer the next day, filtered by tag `Application=courtpulse` once you activate
  that cost allocation tag in the Billing console.

## Alarm response

Each alarm description links to a section of [the observability runbook](../runbooks/observability.md).
AWS-specific first steps:

| Alarm | First look |
| --- | --- |
| `*-game-events-oldest-message-age` | Processor service health and logs (`/ecs/courtpulse-staging/processor`), RDS CPU, then "Event or outbox lag" |
| `*-game-events-dlq-not-empty`, `*-alert-deliveries-dlq-not-empty` | Receive without deleting (`aws sqs receive-message --visibility-timeout 0`), keep bodies out of tickets, then "Dead-letter queue" |
| `*-alb-target-5xx`, `*-alb-unhealthy-hosts`, `*-api-error-logs` | API log group by correlation ID, readiness (database), recent deploys; roll back if a deploy caused it |
| `*-alb-target-p95-latency` | RDS CPU and connections first, then Performance Insights top SQL |
| `*-rds-cpu-high` | t4g burst credits (`CPUCreditBalance`) and Performance Insights; sustained load beyond the baseline is billed in unlimited mode |
| `*-rds-free-storage-low` | Storage autoscaling is off by design; raise `db_allocated_storage_gib` deliberately |
| `*-rds-connections-high` | Tasks per service x Hikari pool (`api_db_pool_size` 5, `worker_db_pool_size` 4); a leak or runaway task count |

## Operating the environment

- **Configuration changes** (environment variables, sizes, roles) update the Terraform-owned
  task-definition template on `terraform apply`; they reach running tasks at the next deploy. Run
  the Deploy workflow manually to roll them out immediately.
- **Desired counts** are Terraform variables and apply immediately. The API count is validated to
  0 or 1 because realtime fanout is in-process; scaling out needs Redis first.
- **Keep `image_tag` recent.** Terraform uses it only when it registers a template revision, but
  ECR keeps only the newest 20 tagged images. Set it to the currently deployed SHA occasionally.
- **Enable the live ingestor:** store the key (`scripts/aws/set-provider-key.sh`), set
  `provider_requests_per_minute` to your BALLDONTLIE tier (free 5, ALL-STAR 60, GOAT 600 requests per
  minute; play-by-play requires GOAT), set `ingestor_desired_count = 1`, and apply. It adds one
  Spot task and one public IPv4 address (~$6-7/month).
- **Tracing:** `enable_tracing = true` adds a pinned ADOT collector sidecar that exports sampled
  traces (`trace_sampling_ratio`, default 0.1) to X-Ray and attaches the image's Java agent. Worker
  polls create traces, so keep sampling low.
- **Nightly shutdown:** `enable_nightly_shutdown = true` scales services to zero and stops RDS at
  `shutdown_schedule`, starts RDS at `database_start_schedule` and services 15 minutes later
  (weekdays, `schedule_timezone`). RDS restarts itself after seven days stopped; the weekday start
  schedule keeps that from surprising you. A `terraform apply` while scaled down restores the
  configured counts.
- **HTTPS origin:** with a domain you control, issue an ACM certificate in the stack's region, point
  a DNS name at the ALB, and set `alb_certificate_arn` and `alb_origin_domain_name`.

## Rollback

Run **Actions > Roll back staging** with an empty `image_tag` (previous deploy) or a specific SHA.
See [the rollback runbook](../runbooks/rollback.md). Locally: `scripts/aws/rollback.sh resolve` then
`scripts/aws/rollback.sh apply <sha>`.

## Teardown

Run `scripts/aws/teardown.sh` and read [the cost and teardown runbook](../runbooks/cost-and-teardown.md).
It disables deletion protection, force-deletes ECR images, destroys the stack, and lists what
remains (the state bucket and, by default, a final RDS snapshot billed as snapshot storage).

## Related runbooks

- [Rollback](../runbooks/rollback.md)
- [Database restore](../runbooks/database-restore.md)
- [Secret rotation](../runbooks/secret-rotation.md)
- [Cost and teardown](../runbooks/cost-and-teardown.md)
- [Observability](../runbooks/observability.md) and [reconciliation](../runbooks/reconciliation.md)
