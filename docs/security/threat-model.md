# CourtPulse threat model and control map

Scope: the public web boundary (Nginx locally, CloudFront in AWS), the Spring API and WebSocket,
the worker processes, PostgreSQL, SQS, the live data provider, email, and the CI/CD pipeline.
Status reflects the repository on 2026-09-28. "Verified" means an automated test or harness in
this repository exercises the control; "Documented" means a runbook or ADR describes a manual or
deployment-time control.

## OWASP API Security Top 10 (2023)

| Risk | CourtPulse control | Evidence |
| --- | --- | --- |
| API1 Broken object level authorization | Every owned resource query is scoped by the JWT `sub`; user IDs are never request input; foreign IDs are indistinguishable from absent ones (404). | Verified: `SecurityAndOwnershipIntegrationTest`, `PersonalizedRulesIntegrationTest`, two-user browser test in `verify-milestone-7.sh`/`-8.sh` |
| API2 Broken authentication | Spring Security resource server validates signature, issuer, audience (`aud` or Cognito `client_id`), expiry, not-before, bounded `sub`, and `token_use`; bearer tokens are refused in query and form parameters; PKCE public client with no secret in the browser. | Verified: `JwtValidationTest`, `SecurityAndOwnershipIntegrationTest`, `AuthProvider.test.tsx` |
| API3 Broken object property level authorization | Responses are explicit DTO records, never persistence entities; private alerts and deliveries never enter public reads or the public WebSocket; operator aggregates omit subjects, addresses, and rule parameters. | Verified: contract test compares every public schema; `verify-observability.sh` asserts no address or subject in telemetry |
| API4 Unrestricted resource consumption | Per-client token buckets by endpoint class (public reads, owner reads, owner writes, operations, WebSocket handshakes) with `429` + `Retry-After`; page size capped at 100; 50 rules per user and 1,000 per game; WebSocket session limit and bounded per-connection buffers; bounded provider responses and request quotas. | Verified: `RateLimitFilterTest`, rules quota tests, `RealtimeHubTest`, `BallDontLieProviderTest` |
| API5 Broken function level authorization | `/api/v1/operations/**` and actuator metrics require the configured operations authority; unclassified routes and other actuator endpoints are denied by default. | Verified: `SecurityAndOwnershipIntegrationTest`, `verify-milestone-7.sh` |
| API6 Unrestricted access to sensitive business flows | Rule creation is idempotent per `Idempotency-Key`, quota-bound, and rate limited; alerts fire once per logical trigger key. | Verified: `PersonalizedRulesIntegrationTest`, duplicate-delivery tests |
| API7 Server-side request forgery | No user-supplied URLs are fetched. The provider base URL is operator configuration, HTTPS-only (plain HTTP only for loopback or the named local simulator), with redirects disabled. Webhooks are deliberately absent. | Verified: `BallDontLieProviderTest` settings validation |
| API8 Security misconfiguration | Strict CSP (no inline script or style, `object-src 'none'`, `form-action 'self'`, `frame-ancestors 'none'`), COOP/COEP/CORP, Permissions-Policy, HSTS at CloudFront, `server_tokens off`, non-root containers with read-only root filesystems in ECS, private RDS, no public Prometheus, explicit WebSocket origins. | Verified: OWASP ZAP baseline (`verify-security-baseline.sh`, `security.yml`), Terraform `trivy config` in CI |
| API9 Improper inventory management | One versioned public contract (`contracts/openapi/courtpulse-v1.yaml`) enforced against the runtime document; AsyncAPI for the realtime channel; generated frontend types fail CI on drift. | Verified: `CourtPulseApiIntegrationTest`, `npm run check:api` |
| API10 Unsafe consumption of APIs | Provider payloads are schema-checked, size-bounded, mapped to a fixed vocabulary, never rendered as HTML, stored as bounded raw evidence, and rejected plays become operator incidents instead of guesses; 429/`Retry-After` and a circuit breaker bound upstream failure. | Verified: `BallDontLiePlayMapperTest`, `ProviderIngestionIntegrationTest`, `verify-milestone-12.sh` |

## Engineering-plan threats

| Threat | Control | Status |
| --- | --- | --- |
| A user requests another user's rule or alert | Owner-scoped repository queries; 404 for foreign IDs | Verified |
| Expired or wrong-audience token | Library validation only; no custom parsing | Verified |
| Rule spam, replay spam, connection floods | Quotas, rate limits, page caps, session limit; replay tooling is operator-only CLI | Verified |
| Unexpected HTML or oversized provider text | Length-bounded, control-character-free descriptions; React escapes all text | Verified |
| Provider key committed or returned to the browser | Key read from environment or Secrets Manager only; redacted `toString`; gitleaks on full history in CI | Verified (gitleaks) |
| SQL injection through search or rule values | Parameterized SQL only; enum-validated rule types; no dynamic rule code | Verified by design and tests |
| Vulnerable library or base image | Pinned versions, Dependabot, Trivy image scans fail CI on fixable HIGH/CRITICAL, CodeQL on public repositories, SBOM and provenance on deployed images | Verified locally; CI runs on push |
| Webhook SSRF | Webhooks out of scope | Documented |
| Token theft through XSS | CSP without inline script, session storage (not local storage), no silent renew, short token lifetimes | Documented residual risk in the [guide](../guide.md#oidc-authentication-and-owned-games) |
| Plain-HTTP CloudFront-to-ALB hop without a custom domain | Secret origin header and CloudFront-only security group; use test identities until a certificate exists | Documented (ADR 0012) |

## Residual risks and deliberate omissions

- Anonymous rate limits key on the remote address, which clients can influence behind proxies;
  a public deployment should add CloudFront or WAF rate-based rules (WAF costs money).
- In-memory rate-limit buckets are per API instance; the API runs one instance by design until a
  shared fanout layer exists.
- Email delivery is at least once; a crash after SES acceptance can send a duplicate.
- CodeQL and dependency review require GitHub Code Security on private repositories; they run
  only when the repository is public.
