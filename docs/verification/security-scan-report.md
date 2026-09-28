# Security scan report

- Date: 2026-09-28
- Environment: macOS (Apple silicon), Docker Desktop 29.8.0, images built from the working tree
  that became the M14 commit
- Tools: Trivy 0.74.0, gitleaks 8.30.1, OWASP ZAP stable (baseline), npm 11.17.0, actionlint
  1.7.12, shellcheck stable, TFLint 0.64.0 with the AWS ruleset, Terraform 1.16.4

CI repeats every check on each push (`ci.yml`, `security.yml`); CodeQL and dependency review
run there when the repository is public. This file records the local run and what it changed.

## Container images (Trivy, HIGH and CRITICAL)

| Image | Before | After | Runs as |
| --- | --- | --- | --- |
| API | 67 fixable (3 CRITICAL Tomcat CVEs, OpenSSL, musl, libexpat, ...) | 0 | `courtpulse` |
| Worker | 64 fixable | 0 | `courtpulse` |
| Web (Nginx + SPA) | 44 fixable (OpenSSL, pcre2, zlib, ...) | 0 | `101` |
| Provider simulator | 67 fixable (incl. Jackson 2.19.2) | 0 | `courtpulse` |

Changes that produced the result:

- Temurin JRE `21.0.8_9` to `21.0.12_8`, Nginx unprivileged `1.29.1` to `1.31.6`, and
  PostgreSQL `17.6` to `17.11` (local parity with the RDS engine version).
- `apk upgrade --no-cache` in the Java runtime stages, because Alpine fixes (libexpat at the time
  of this scan) can land before the next base-image release.
- Tomcat pinned to 11.0.26 through Gradle constraints (Spring Boot 4.1.1 manages 11.0.24:
  CVE-2026-65182, CVE-2026-65905, CVE-2026-68525). Remove once Spring Boot manages a fixed version.
- The providers module and simulator moved from Jackson 2.19.2 to 2.22.1, the version the
  Spring Boot BOM resolves for the other modules.

## Secrets (gitleaks, full history and working tree)

Initial run: 9 findings, all reviewed. Seven were documentation placeholders
(`Authorization: Bearer PASTE_ACCESS_TOKEN`) or the local simulator's non-secret key in README
examples, one was a WebSocket handshake nonce in a script, and one was a fixed password for the
throwaway M6 Compose project. The M6 harness now generates its password per run; `.gitleaks.toml`
allowlists exactly those four patterns with reasons. Final run: no leaks in 13 commits of history
or in the working tree.

## Web boundary (OWASP ZAP baseline, passive)

Initial run: 0 failures, 61 passes, and warnings for a CSP without `form-action`, missing
Permissions-Policy, missing Cross-Origin-Opener/Embedder/Resource-Policy headers, and an Nginx
version in the `Server` header. Nginx (and the CloudFront response-headers policy in Terraform)
now send `object-src 'none'` and `form-action 'self'`, `Permissions-Policy`, COOP `same-origin`,
COEP `require-corp`, CORP `same-origin`, and `server_tokens off`. Final run: **0 failures, 0
warnings, 65 passes**, and two informational rules ignored in `.zap/rules.tsv` with reasons
(cacheable hashed assets; words inside the minified third-party bundle). Reproduce with
`scripts/verify-security-baseline.sh`.

## Dependencies, infrastructure, workflows, and scripts

- `npm audit` (all and production-only): 0 vulnerabilities.
- `trivy config infra/terraform`: 0 HIGH or CRITICAL; accepted lower findings carry inline
  justifications (for example no WAF or customer-managed KMS keys, for cost).
- TFLint (AWS ruleset), `terraform validate`, and mocked `terraform test` (5 runs): clean.
- actionlint on all workflows and shellcheck on every script: clean.

## Application-level controls added in this milestone

Per-client rate limits by endpoint class with `429` and `Retry-After` (OWASP API4), documented
in the OpenAPI contract for every operation. See [the threat model](../security/threat-model.md)
for the full OWASP API Top 10 mapping.
