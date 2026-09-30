# Changelog

CourtPulse was built in milestones, one commit each. Dates are commit dates. Design decisions
are in [docs/adr](docs/adr); the [guide](docs/guide.md) documents every capability in detail.

## 1.1.0 — 2026-09-30 — Real-game replay

- Replay completed real NBA games (the 2026 playoffs by default, or any imported season) as live
  games, through the same ingestion, processing, alert, and realtime path as a live feed. Pause,
  resume, change speed (1x to 120x), skip to the final, or replay again from the browser.
- Alerts, rules, and emails use names ("Hal Quill reached 16 points", "3-point game with 0:06
  left in Q4") instead of IDs; alert details are written in plain words; the game page shows
  your own alerts for that game.
- `scripts/verify-replay.sh` (weekly in CI) replays games end to end; with `REAL_DATA=1` it
  checks real playoff games, including overtime, against their official final scores.
- Local sign-in lasts 60 minutes, matching Cognito (it was Keycloak's 5-minute default).
- The M7 harness's stale sign-in check is fixed, and every harness now runs weekly in CI.

## 1.0.0 — 2026-09-28 — Release polish (M16)

- One-command local demo (`scripts/demo.sh`, `make demo`): the full stack with a simulated live
  game, sign-in, alert rules, and email capture, isolated in its own Compose project with
  generated local-only credentials.
- New README as an overview and quick start; the full reference moved to `docs/guide.md`.
- The rule form suggests the linked game's teams and scoring players by name.
- The header shows a readable user name instead of the OIDC subject.
- Identity provisioning is idempotent. Added `Makefile`, this changelog, and a retrospective.

## M15 — 2026-09-28 — Performance and recovery

- k6 and synthetic-load harness for the plan's stress targets; publisher batching raised
  saturation drain from about 99 to 455-578 events/s. Sustained 200 events/s: p95 82 ms.
- Four recovery drills (processor SIGKILL, PostgreSQL restart, SQS partition, SIGTERM
  mid-publication), which exposed and fixed five ways a transient failure could permanently
  stall a game (ADR 0014).
- Realtime `emittedAt` now matches the contract's RFC 3339 string.

## M14 — 2026-09-28 — CI, security scanning, and rate limiting

- GitHub Actions CI, security (gitleaks, Trivy, ZAP, CodeQL on public repositories), weekly
  acceptance, staging deploy and rollback workflows, and Dependabot.
- Container, dependency, and edge findings remediated; per-client API rate limits with `429`.

## M13 — 2026-09-28 — AWS staging infrastructure and cloud parity

- Terraform for a complete staging environment (VPC, Fargate, RDS, SQS, Cognito, SES,
  CloudFront, alarms, budget), validated offline and never applied.
- Cognito token validation, SES email, WebSocket origin checks, and a release rehearsal.

## M12 — 2026-09-28 — Live provider ingestion and overtime

- BALLDONTLIE adapter behind `LiveGameProvider`, a local simulator, rate limiting, circuit
  breaking, per-game freshness, corrections as revisions, and periods through six overtimes.

## M11 — 2026-09-28 — Local observability

- OpenTelemetry traces across processes through the outbox and SQS, Prometheus alerts, Grafana,
  and runbooks.

## M10 — 2026-09-24 — Corrections and reconciliation

- Provider revisions, gap detection, deterministic reconciliation, and corrected alerts.

## M9 — 2026-09-22 — Reliable alert delivery

- In-app and email delivery through a separate queue with retries, DLQ, and history.

## M8 — 2026-09-22 — Personalized alert rules

- Structured rule templates (player points, close game, scoring run), owner isolation, and
  indexed evaluation inside the checkpoint transaction.

## M7 — 2026-09-21 — OIDC authentication and owned games

- Authorization Code with PKCE, a resource-server API, and followed games.

## M6 — 2026-09-21 — Realtime WebSocket updates

- Versioned WebSocket hints from a realtime outbox, with HTTP resynchronization.

## M5 — 2026-09-21 — Web dashboard

- React 19 and TypeScript dashboard generated from the OpenAPI contract, served by Nginx.

## M4 — 2026-09-20 — Read API

- Versioned REST API with ETags, opaque cursors, and bounded queries.

## M3 — 2026-09-20 — Queue-backed processing

- Leased outbox publication to SQS FIFO, idempotent consumers, and a DLQ.

## M2 — 2026-09-20 — Durable PostgreSQL processing

- Transactional outbox, processed-event identities, and checkpoints.

## M1 — 2026-09-20 — Deterministic replay foundation

- Infrastructure-free domain model and replay with a stable final-state checksum.
