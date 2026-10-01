# Retrospective

CourtPulse was built in sixteen milestones between 2026-09-20 and 2026-09-28, following a
product and engineering plan (not published): replay-first foundations, then durability, queues,
the API and dashboard, realtime updates, identity, alert rules and delivery, corrections, observability, a
live provider, cloud infrastructure, CI and security, performance and recovery, and release
polish. This page records what worked, what the plan did not anticipate, and where the finished
system deliberately differs from the plan.

## What worked

- **Replay first.** Building the domain as a pure reducer with a final-state checksum before any
  infrastructure meant every later layer (PostgreSQL, SQS, the live provider) could be checked
  against a known answer. Regressions showed up as checksum mismatches, not as vague symptoms.
- **Durability by construction.** The transactional outbox, per-game FIFO groups, and
  processed-event identities made redelivery, crashes, and duplicate provider data ordinary cases
  instead of incidents. Rule evaluation inside the checkpoint transaction gave exactly-once
  alerts without a coordination protocol.
- **Contracts as code.** The web client's types are generated from the OpenAPI document and CI
  fails on drift, so the API and dashboard could not disagree silently.
- **Isolated harnesses.** Every end-to-end check runs in a uniquely named Compose project with its
  own volumes and ports and cleans up after itself, so harnesses never touched development data
  and could run side by side.

## What measurement found

Load tests and failure drills found problems that code review and unit tests had not:

- The outbox publisher sent one SQS message per call and capped throughput at about 99 events/s.
  Batched, concurrent sends raised the drain rate to 455-578 events/s.
- Five failure-handling defects could each turn a short, harmless event (a network blip, a
  deployment's SIGTERM, a 15-second SQS outage) into a permanently stalled game, because a failed
  outbox row blocks its game by design. All five are fixed and covered by drills
  ([ADR 0014](adr/0014-batched-publication-and-transient-retry.md)).
- Realtime messages sent `emittedAt` as a number although the contract promised a timestamp
  string; contract tests checked the schema's shape but not this serialization.
- Test infrastructure has its own failure modes: restarting LocalStack discards queues (real SQS
  does not), a Compose variable that is not passed through silently does nothing, and a sleeping
  laptop suspends the Docker VM mid-measurement. The harnesses now guard against each.

The lesson was the same every time: a claim about resilience or performance is a hypothesis
until a harness has tried to break it.

## Where the result differs from the plan

| Plan | Result | Why |
| --- | --- | --- |
| Deploy to AWS (ECS, RDS, SQS, Cognito, Redis) | Terraform, deploy and rollback scripts, and workflows written and validated offline; never applied | Running AWS costs money and needs the owner's account; the guide covers apply, demo, and teardown |
| Live BALLDONTLIE play-by-play | Adapter built to the documented schema and exercised against a local simulator | Play-by-play requires a paid tier and a terms review |
| Redis or Valkey for realtime fanout | Single-instance WebSocket hub with HTTP resynchronization | Enough for a portfolio deployment; shared fanout is only needed to run more than one API instance |
| 10,000 alert evaluations from one scoring event | At most 1,000 owned rules per game; 130,000 evaluations across ten hot games in 5 s | Keeps rule evaluation inside the checkpoint transaction and under the 500 ms processing budget ([performance report](verification/performance-report.md)) |
| Operations dashboard | Operations API endpoints and Grafana dashboards; no operations screen in the web app | Grafana covers the operator's needs locally |

## After 1.0

Three releases followed on 2026-09-30 and 2026-10-01 ([changelog](../CHANGELOG.md)):

- **Real games.** Completed NBA games replay through the same provider path as a live feed
  ([ADR 0015](adr/0015-real-game-replay.md)). When the first playoff dataset stopped after 60
  games, a second layout without timestamps was added; its pacing was calibrated against the 60
  games present in both, and all 85 games of the 2026 playoffs were replayed to their recorded
  finals.
- **A redesigned dashboard.** Three independent UI reviews (visual, design-system, and UX
  structure) drove a token-based design system, new navigation, team-colored badges, playoff
  game titles, and restrained motion.
- **Demo fixes found by using it.** Replays exposed a LocalStack startup race and an Nginx proxy
  that cached the API's address; both now recover on their own.

## What comes next

1. Apply the staging environment within a budget, run the smoke tests and a rollback, and replace
   the local-equivalent claims in the reports with measured AWS numbers.
2. Subscribe to the provider's paid tier (after reviewing its terms) and verify the adapter's
   assumptions against real games.
3. Add repair tooling for outbox rows that fail permanently, which is now the only way a game can
   be blocked.
4. Move rule fanout out of the checkpoint transaction (idempotent jobs keyed by trigger) if a game
   ever needs more than 1,000 rules, and add a shared fanout layer before running more than one
   API instance.
