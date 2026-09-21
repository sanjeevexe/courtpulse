# ADR 0005 Serve a typed React dashboard with ETag-aware polling

- Status: Accepted
- Date: 2026-09-21

## Context

CourtPulse now exposes stable, PostgreSQL-backed REST resources for game summaries, snapshots,
canonical events, and logical alerts. The next user-facing increment needs to make that durable data
usable on desktop and mobile without introducing a second source of truth or prematurely committing
to a realtime transport. The UI must preserve contract drift detection, conditional snapshot reads,
safe failure states, deep-route refreshes, and a production-shaped same-origin deployment boundary.

## Decision

Build `apps/web` as a React 19 single-page application written in strict TypeScript and bundled by
Vite. Use React Router for the slate and game-detail routes, and TanStack Query for server-state
caching, retries, pagination, conditional refreshes, and document-visibility behavior.

React provides a focused component model for the score, event, alert, loading, empty, and error
states. TypeScript makes nullable cursor, data-status, ETag, and Problem Details behavior explicit.
Vite supplies a small development and production build surface with a local reverse proxy and no
framework server runtime. TanStack Query separates durable server state from transient UI state and
provides bounded cache and refetch behavior without inventing an application-specific polling
scheduler.

### Contract-derived types

Generate browser API types from `contracts/openapi/courtpulse-v1.yaml` with `openapi-typescript` and
check the generated file into source control. A drift command regenerates into a temporary file and
compares exact output, so a contract change cannot silently leave the browser client stale. The
handwritten client owns transport concerns such as timeouts, ETags, sanitized Problem Details, and
network errors; it does not duplicate public DTO shapes.

### Durable authority and polling

PostgreSQL-backed HTTP responses remain authoritative. The browser never derives scores, player
totals, event history, or alerts from local actions. Snapshot requests retain the response ETag and
send `If-None-Match`; a 304 reuses the cached typed representation. Final games do not poll. Fresh
live games poll every 15 seconds, stale or processing-blocked games back off to 30 seconds, and other
non-final states poll every 60 seconds. Background interval refetching is disabled, and stale data is
eligible for refresh when focus returns.

This policy gives users bounded freshness with simple recovery semantics before a realtime protocol
exists. Pagination remains cursor based, and client-side identity suppression protects the display
from overlapping pages without changing durable ordering.

### Production boundary

Use a multi-stage container build with pinned Node 24.21.0 only in the build stage. Serve the output
from unprivileged Nginx as user 101. Nginx provides SPA route fallback, immutable asset caching,
security headers, an explicit health endpoint, and a same-origin reverse proxy for `/api`. Only API
liveness and readiness are exposed through the web boundary; other actuator routes are rejected.
The runtime image contains static files and Nginx configuration, not Node.js, dependencies, source,
test reports, build caches, or credentials.

### Deferred capabilities

WebSockets are deferred until a versioned hint protocol can preserve HTTP resynchronization and
durable outbox publication. Authentication and ownership, personalized rules, email delivery, live
provider ingestion, shared Redis fanout, Terraform, and AWS deployment remain later milestones.

## Consequences

- Users can inspect the seeded durable game, all 20 events, player totals, and alerts on desktop and
  mobile through accessible loading, empty, error, and data-status states.
- OpenAPI is the single public type contract for both backend verification and browser compilation.
- Conditional requests avoid retransmitting unchanged snapshots, while polling remains predictable
  and safe across refreshes and temporary outages.
- The static web service can remain available while the API is temporarily unavailable and can show
  a recoverable error.
- Polling adds bounded request overhead and cannot provide possession-level latency. A later
  realtime milestone should deliver versioned hints and retain HTTP as the recovery authority.
- The unauthenticated read surface is suitable only for the current local/product-development stage.
