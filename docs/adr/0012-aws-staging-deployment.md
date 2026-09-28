# ADR 0012: Deploy a cost-bounded AWS staging environment with Terraform

- Status: Accepted (not applied)
- Date: 2026-09-28

## Context

CourtPulse is correct locally: PostgreSQL is authoritative, SQS FIFO carries leased outbox work,
the API serves REST and single-instance WebSocket hints, and workers own publication, delivery,
and reconciliation. Release 3 of the engineering plan requires a reproducible AWS environment,
automated deployment with migrations, a replay canary, rollback, alarms, and an explicit cost and
teardown story. The owner is a student: every always-on dollar matters, no paid domain exists, and
nothing may be created by automation without a deliberate human `terraform apply`.

## Decision

### Infrastructure as code

Terraform (>= 1.10, AWS provider `~> 6.66`) lives in `infra/terraform`. A `bootstrap` root with
local state creates one versioned, SSE-S3, public-access-blocked, TLS-only state bucket. The
`environments/staging` root uses the S3 backend with partial configuration and S3-native locking
(`use_lockfile`), so no DynamoDB table exists. Reusable modules cover network, data, messaging,
identity, ecs-service, edge, observability, cost, and cicd. Provider lock files are committed for
Linux and macOS on both architectures. Every resource carries `Application`, `Environment`, `Owner`,
`CostCenter`, and `ManagedBy` through provider default tags. Validation is offline: fmt, validate,
TFLint with the AWS ruleset, Trivy, and `terraform test` plans against mock providers.

### Network without NAT

A two-AZ VPC has two public subnets (ALB and Fargate tasks) and two private subnets that contain
only RDS and have no internet route. There is no NAT gateway (about $32/month plus data processing
in us-east-1). Tasks receive public IPv4 addresses and reach ECR, Secrets Manager, SQS, SES,
CloudWatch Logs, Cognito, and the data provider over HTTPS. Their security groups allow no ingress
except ALB-to-API on 8080, and egress only to 443 and to PostgreSQL. A free S3 gateway endpoint
keeps ECR layer downloads on the AWS network. The rejected alternatives were a NAT gateway and
interface endpoints for each AWS API (roughly $7/month per endpoint per AZ, more than the public
addresses they replace). Public IPv4 is billed at $0.005 per address-hour, so the count of running
tasks is itself a cost lever and is documented.

### Edge without a custom domain

One CloudFront distribution is the only public entry point. Its default `*.cloudfront.net`
certificate gives viewer HTTPS, which Cognito requires for callback URLs. The SPA is served from a
private bucket through Origin Access Control. A viewer-request CloudFront Function rewrites
extension-less paths to `/index.html`; distribution-wide custom error pages were rejected because
they would replace API 404 Problem Details. `/assets/*` is cached for a year as immutable content,
and `index.html` revalidates on every request. A response-headers policy applies the same CSP as
`apps/web/nginx.conf` (plus the two Cognito origins), HSTS, `nosniff`, `DENY` framing, and the
referrer policy to both SPA and API responses.

`/api/*`, `/ws/*`, and `/v3/api-docs*` go uncached to an internet-facing ALB with
`Managed-AllViewerExceptHostHeader`. Forwarding all viewer headers except Host passes
`Authorization`, `Origin`, and the WebSocket upgrade headers, while the origin receives its own
Host. That works for the HTTP origin and for an HTTPS origin whose certificate names the origin (an
`AllViewer` policy would send `*.cloudfront.net` as Host and SNI, which no origin certificate can
match), and the API never trusts a viewer-chosen Host. The ALB admits only the CloudFront
origin-facing managed prefix list; its listener returns 403 unless a secret
`X-CourtPulse-Origin-Verify` header (a Terraform `random_password`) is present and the path is an
API path. The idle timeout is 120 seconds for WebSockets.

Without a domain there is no certificate that CloudFront can validate for the ALB, so the
CloudFront-to-ALB hop is HTTP and bearer tokens cross it unencrypted on AWS and public networks. This
is accepted only for staging with test identities. Supplying `alb_certificate_arn` and
`alb_origin_domain_name` switches to an HTTPS listener (TLS 1.2/1.3 policy) and an `https-only`
origin. CloudFront VPC origins, which would keep the ALB private, and AWS WAF (about $5 per web ACL
per month plus rules) are recorded as production follow-ups.

### Compute and deployment ownership

ECS on Fargate runs five services: `api` (0.5 vCPU, 1 GiB), `processor`, `delivery`,
`reconciliation`, and `ingestor` (0.25 vCPU, 0.5 GiB each), plus `migrate` and `canary` one-off
task families. The API desired count is validated to at most one because realtime fanout is
in-process; horizontal scaling first requires Redis or another shared fanout. The ingestor
defaults to zero until a provider key exists. Workers default to Fargate Spot (roughly 70% cheaper);
interruptions are safe because leases expire, SQS redelivers, and PostgreSQL idempotency is
authoritative. Containers run with a read-only root filesystem and a task-storage `/tmp`, init
process, non-blocking log delivery, and heartbeat or liveness health checks. Rolling deployments
use 100/200 percent bounds and the deployment circuit breaker with automatic rollback. Container
Insights and ECS Exec are off by default.

Terraform owns each task-definition template (environment, secrets, roles, sizes, health checks).
CI owns the image: it copies the family's latest ACTIVE revision, replaces the application image
with an immutable git-SHA tag, tags the new revision with its provenance, and updates the service.
Services ignore `task_definition` drift so a later `terraform apply` never reverts a deployed
image; Terraform configuration changes reach tasks at the next deploy. The first environment is
created in two steps: a targeted apply of the registries, deploy role, and budget, then a full
apply once images for `image_tag` exist.

The deploy pipeline builds the SPA in a job without cloud credentials, builds images with BuildKit
provenance and SBOM attestations, runs `--migrate` as a one-off task and fails on a non-zero exit,
updates every service and verifies that each primary deployment completed on the new revision (a
circuit-breaker rollback is also "stable" and is treated as failure), runs `--canary`, publishes
the SPA, invalidates `index.html`, and smoke-tests through CloudFront. Rollback re-points every
service to the previous CI revision or a named image tag and republishes the SPA from that commit.
Migrations are never reverted; expand-and-contract keeps the previous release compatible.

### Data, identity, messaging, and secrets

RDS PostgreSQL 17 (`db.t4g.micro`, 20 GiB gp3, encrypted, private, single-AZ by default) forces
TLS, logs statements slower than 500 ms, keeps seven days of backups for point-in-time recovery,
uses Performance Insights' free retention, and has deletion protection plus a final snapshot by
default. RDS generates and stores the master password in Secrets Manager; ECS injects the
username and password by JSON key, so no database credential exists in code or Terraform state.
The empty provider-key secret is created by Terraform and filled out of band. Using the master
user for application and migrations is a staging simplification; production should separate a
migration role from a runtime role.

Cognito uses the Lite feature plan, email sign-in, admin-created users by default, a strong
password policy, optional TOTP, and a public app client with Authorization Code, PKCE (enforced by
the SPA), and 60-minute access and ID tokens. The API validates access tokens by `client_id`,
`token_use=access`, and `cognito:groups` mapped to `ROLE_courtpulse-ops`. The CSP needs the
hosted-UI host and the client needs the CloudFront domain; the cycle is broken by deriving the
hosted-UI host from the region and a generated prefix rather than from the domain resource.

Four FIFO queues mirror the local topology with SSE-SQS, explicit deduplication, three-receive
redrive, TLS-only queue policies, and DLQs restricted to their source queue. Task roles are
least-privilege per process: every worker-image role may resolve all four queue URLs because the
worker resolves them at startup, the processor and delivery roles can send, receive, delete, and
change visibility only on their own queue, reconciliation can only read queue attributes, and the
delivery role may call `ses:SendEmail` only with its configured `ses:FromAddress`.

### Observability and cost

Ten CloudWatch alarms (queue age, both DLQs, ALB 5xx, unhealthy targets, p95 latency, RDS CPU,
storage, and connections, and API ERROR log lines) notify one SNS topic and fit the free alarm
tier. The topic is unencrypted by default because CloudWatch cannot publish to a topic encrypted
with the AWS managed `aws/sns` key; an optional customer-managed key is available. One dashboard
covers ALB, SQS, RDS, and per-service ECS utilization. An account-wide monthly budget (default $25)
emails at 50, 80, and 100 percent actual and 100 percent forecast. Optional EventBridge Scheduler
schedules scale services to zero and stop RDS overnight.

## Consequences

- Nothing is applied by this change. Every AWS charge follows a human `terraform apply`.
- Always-on staging is estimated at roughly $80-100/month in us-east-1 (see
  `docs/deployment/aws-staging.md`), well above the $25 budget. The ALB and its public addresses
  alone approach $24/month even with nightly shutdown, so the supported low-cost pattern is create,
  demonstrate, and destroy; the budget is an alert, not a spending cap.
- The CloudFront-to-ALB hop is unencrypted until a domain and certificate exist. Only test
  identities belong in staging.
- The WebSocket handshake arrives with the ALB's Host and `X-Forwarded-Proto: http`, so Spring's
  default same-origin check cannot succeed; the API must accept the configured CloudFront origin
  (`COURTPULSE_REALTIME_ALLOWED_ORIGINS`).
- ECS reads secrets only at task start. RDS rotates its managed master secret on a schedule, after
  which tasks recover by failing health checks and restarting; the rotation runbook forces a
  deployment instead of waiting.
- Deploy and rollback are scripts that humans can run with the same variables CI uses; they
  depend on revision tags, so manually registered task definitions are not rollback targets.
- WAF, VPC flow logs, CloudFront and ALB access logs, customer-managed KMS keys, Multi-AZ, and
  private task subnets are deliberate cost omissions with documented switches or follow-ups.

## Application changes and local rehearsal

The application satisfies this contract without AWS-specific branches: the worker image's
entrypoint takes a mode (`--processor-daemon`, `--delivery-daemon`, `--reconciliation-daemon`,
`--ingest-daemon`, `--migrate`, `--canary`); `COURTPULSE_SQS_ENDPOINT=aws` selects the regional
endpoint and task-role credentials; `COURTPULSE_EMAIL_PROVIDER=ses` sends through the SES
`SendEmail` API (the IAM action and `ses:FromAddress` condition above) from one verified sender;
the API accepts Cognito access tokens by matching `client_id` and `token_use=access`, publishes
Cognito's logout endpoint to the browser, allows the WebSocket handshake only from the configured
site origin, and pings idle sockets every 25 seconds so the ALB idle timeout never closes a quiet
subscription. `scripts/verify-release-rehearsal.sh` runs the release sequence locally and for free:
the one-off migrate task, services, the canary, an alert email through the SES API on LocalStack,
the origin policy, and a rollback of the API to the release before the newest migration against
the newer schema, then a roll-forward. The SES v1 API was chosen over v2 because both use the
same IAM action while only v1 can be exercised against the free LocalStack edition.

