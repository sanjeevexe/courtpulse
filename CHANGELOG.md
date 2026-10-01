# Changelog

CourtPulse was built in milestones, one commit each. Dates are commit dates. Design decisions
are in [docs/adr](docs/adr); the [guide](docs/guide.md) documents every capability in detail.

## 1.3.0 — 2026-10-01 — The complete 2026 playoffs

- Replays cover all 85 games of the 2026 NBA playoffs, first round through the Finals. The
  importer now also reads stats.nba.com play-by-play (`nbastatsv3_*`), dated from the matching
  shot-detail dataset and paced from the game clock; games with real timestamps are kept.
- The demo shows only real games: it no longer seeds the synthetic game or runs the simulated
  live provider (both remain in the test harnesses), and it imports the full playoffs.
- LocalStack reports healthy only after its queues exist, which fixes a startup race that could
  crash the replay worker and stop `make demo` early.
- Playoff games carry their title ("Western Conference Finals · Game 1", "NBA Finals · Game 5"),
  derived from the NBA game ID and the teams' conference, on game cards, the scoreboard, Replays,
  alerts, rules, and toasts.
- Team badges use each franchise's color (team labels are the one exception to the three-color
  scheme).
- The local sign-in page (Keycloak) has a "Back to CourtPulse" link, through a small login theme
  that extends Keycloak's default.
- The Scores page loads 12 games at a time, so every page fills whole rows in one-, two-, and
  three-column layouts.
- The web container's nginx re-resolves the API through Docker DNS, so recreating the API (for
  example on `make demo` after a rebuild) no longer leaves the dashboard failing with 502s.

## 1.2.0 — 2026-09-30 — Web redesign

- A new design system for the web app: spacing, type, color, radius, and motion tokens; self-hosted
  Inter and Barlow Condensed fonts; one page-header, card, panel, and empty-state pattern.
- Navigation: Scores, Replays, Alerts, and Rules in the header with an active indicator, an
  account menu for personal pages and sign-out, and a bottom tab bar on phones.
- Game cards and the scoreboard show team badges in team colors, a pulsing LIVE pill with the
  period and clock, and dimmed losing teams on finals. The game page puts alerts beside the
  play-by-play and adds a player-leader chart; the duplicate "Recent possessions" list is gone.
- Subtle motion: scores tick when they change, cards and rows ease in, a sliding indicator on
  segmented controls, skeleton shimmer, and a toast whenever one of your alerts fires anywhere
  in the app. All motion is transform/opacity only and respects reduced-motion settings.
- My Games shows live score cards instead of raw game IDs; the rule form suggests games from the
  slate; My Alerts shows delivery as status chips; notification email is a switch.
- Fan-facing copy replaces internal wording ("Durable game feed", "PostgreSQL-backed").
- A three-color scheme: charcoal neutrals, court blue for actions, selection, progress, and
  success, and coral red only for live games, delays, errors, and destructive actions. Team
  badges are monochrome, and scoring plays carry a blue marker.

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
