# ADR 0011: Correlate durable work and observe the local system

- Status: Accepted
- Date: 2026-09-24 (accepted 2026-09-28 after `scripts/verify-observability.sh` passed)

## Context

The API already reports request correlation IDs and some rule and delivery aggregates, but a
fixture import, a leased outbox row, an SQS receive, a reconciliation, and an email attempt run
in different processes. Request-local logging alone cannot reconstruct that path. The local
demo also needs to show failure and recovery without using AWS or exposing private rule data.

## Decision

Use W3C `traceparent` as the only durable trace carrier. The OpenTelemetry Java agent creates
HTTP, JDBC, and queue-client spans when the optional local observability profile is enabled.
Small explicit spans mark fixture/correction ingest, outbox publish, queue consume, event
processing, rule evaluation, reconciliation, and delivery. Ingestion records a validated
traceparent on the outbox row; its publisher creates a child context and transmits that
context in both the canonical envelope and SQS message metadata. Delivery outbox and SQS
messages carry only the same traceparent and an opaque delivery ID, never an address or rule
parameter. A reconciliation request carries its triggering context through the database
claim. If a producer has no sampled trace, the consumer starts its own trace without
inventing a parent. The original correlation ID remains a separate client-facing request ID.

Operational measurements are fixed-label aggregates, not event-level labels. Workers persist
bounded heartbeat and queue-depth observations in PostgreSQL. The API exports cached,
aggregate feed freshness, event lag, outbox backlog/age, queue/DLQ depth, duplicate
suppression, reconciliation, delivery, and worker-health gauges. A negative freshness or
observation age means no observation yet; it is not silently treated as healthy. Rule type,
queue type, destination, and worker type are finite vocabularies. IDs, subjects, addresses,
private rule parameters, authorization headers, and payloads are prohibited as metric labels
or trace attributes. Operators may use opaque IDs in traces for causal diagnosis, but not
as metric labels. Trace retention is one day and metrics retention is seven days locally.

The optional Compose overlay runs a pinned Collector, Tempo, Prometheus, and Grafana in a
separate `courtpulse-observability` project. The Java agent is pinned by version and SHA-256
and baked into the API and worker images with a Dockerfile checksum; it stays inert unless
`JAVA_TOOL_OPTIONS` adds `-javaagent`, which only this overlay (or opt-in cloud tracing) does. Prometheus scrapes a basic-auth-protected internal
route; the standard actuator metrics route remains operations-authorized and Prometheus is
not published on a host port. Grafana binds to loopback with a generated local password.
Telemetry is local-first and has no external exporter or cloud credential. A controlled
gap and worker-stop exercise must demonstrate both trace linkage and firing alerts; container
startup by itself is not acceptance.

## Consequences

Trace context adds nullable metadata columns without changing event, alert, or delivery
identity. Existing pre-M11 rows can still be processed as new root traces. The optional agent
adds runtime cost only in the observability profile; ordinary isolated M9/M10 verification
keeps its existing Compose layout. Baking the agent adds roughly 20 MB per Java image and a
build-time download from Maven Central, in exchange for identical local and cloud artifacts.
Approximate SQS depth is explicitly an observation and
its age is visible so stale samples cannot be mistaken for current queue health. A future
cloud deployment will need its own authenticated scrape and sampling/retention review; this
ADR does not authorize Terraform or AWS work.

## Verification

`scripts/verify-observability.sh` runs in a uniquely named project and removes only its own
volumes. The accepted run (2026-09-28) recorded one 710-span trace crossing three processes
(fixture ingest, queue processor, delivery worker), an API response whose `X-Trace-ID` was found
in Tempo, a correction trace reaching the reconciliation worker, rejected unauthenticated and
wrong-credential scrapes, no private address or subject in any trace or scrape, and four real
alerts: blocked reconciliation fired after 69 s and cleared after new evidence; a stopped
delivery worker fired `CourtPulseWorkerStale` after 131 s and cleared 8 s after restart; a
poison message reached the FIFO DLQ through real redrive and fired `CourtPulseDlqNonEmpty`
after 41 s. Evidence is written to `build/verification/observability/<project>/`.
