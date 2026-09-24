# CourtPulse

CourtPulse is a replay-first basketball event platform. Its current production-shaped path is:

```text
synthetic fixture -> PostgreSQL transactional outbox -> lease-based publisher
                  -> SQS FIFO game-events queue -> queue consumer
                  -> durable processor -> PostgreSQL checkpoint, identities, alert, realtime outbox
                                                        |
HTTP client -> Spring MVC -> query service -> bounded JDBC reads -> PostgreSQL
Browser -> non-root Nginx -> React dashboard
                         \-> same-origin /api proxy -> Spring MVC
                         \-> /ws/v1/games -> versioned realtime hints
```

PostgreSQL is the durable source of truth for the versioned public read API, owned game follows,
structured personalized rules, and private alert history. The API never reads
from SQS and does not need LocalStack to serve requests. The React dashboard consumes only the
versioned REST contract through a same-origin Nginx boundary. The infrastructure-free and direct
PostgreSQL replay commands remain available. WebSocket messages are non-authoritative hints; clients
resynchronize through HTTP after gaps and reconnects. OIDC authentication protects user-owned and
operational resources. Personalized alerts are private HTTP resources and never enter the public
WebSocket stream; Redis, email, live providers, and cloud deployment remain outside this milestone.

## Prerequisites and tests

- Java 21
- Node.js 24.21.0 (the exact version in `apps/web/.nvmrc`)
- Docker Desktop with a healthy Linux engine and Docker Compose
- Internet access for the first dependency and image download

No global Gradle, PostgreSQL, LocalStack, or AWS CLI installation is required. Verify and test:

```bash
docker version
docker info
docker compose version
./gradlew clean test --rerun-tasks --console=plain
```

Docker is required for final validation; a skipped container test is not a successful validation.
Run the PostgreSQL/LocalStack messaging suite alone with:

```bash
./gradlew :modules:messaging:test --rerun-tasks --console=plain
```

Use the pinned frontend runtime and install the locked dependency graph:

```bash
cd apps/web
nvm use
npm ci
```

Generate TypeScript definitions from the checked OpenAPI contract, or verify that the checked-in
generated file has not drifted:

```bash
npm run generate:api
npm run check:api
```

The generated `apps/web/src/api/generated/schema.ts` is intentionally version controlled. Dependency
trees, production bundles, coverage, Playwright reports, traces, and Vite/Vitest caches are ignored.

Build and smoke-test the executable queue application:

```bash
./gradlew :apps:queue-replay-cli:bootJar
./gradlew :apps:queue-replay-cli:run --args='--help'
java -jar apps/queue-replay-cli/build/libs/queue-replay-cli-0.1.0-SNAPSHOT.jar --help
```

Build the executable API application:

```bash
./gradlew :apps:api:bootJar
./gradlew :apps:api:run
```

## Local PostgreSQL and SQS

Choose a local-only password and start both pinned services:

```bash
export COURTPULSE_DB_PASSWORD='courtpulse-local-dev'
docker compose config
docker compose up -d postgres localstack
docker compose ps
```

PostgreSQL and LocalStack must report healthy. The idempotent LocalStack ready hook creates:

- `game-events.fifo`: FIFO source queue, `MessageGroupId=gameId`, 8-second visibility timeout,
  2-second long poll, and explicit deduplication IDs.
- `game-events-dlq.fifo`: FIFO dead-letter queue.
- A redrive policy that moves a message after three failed receives.

Inspect the topology:

```bash
docker compose exec localstack awslocal sqs list-queues
docker compose exec localstack awslocal sqs get-queue-attributes \
  --queue-url http://sqs.us-east-1.localhost.localstack.cloud:4566/000000000000/game-events.fifo \
  --attribute-names All
```

LocalStack is pinned to `localstack/localstack:4.4.0`. Only a configured endpoint override receives
the dummy local credentials; without an override, the AWS SDK default credential chain applies. No
real credentials or generated database data are committed.

## Queue-backed replay

The bounded command imports only when `--reset-import` is supplied. That explicit local-demo option
also purges both queues. Publish and drain the full fixture:

```bash
./gradlew :apps:queue-replay-cli:run --args='--reset-import --run'
```

Expected logical result:

```text
Imported: raw=20 canonical=20 outbox=20
Publisher: claimed=20 sent=20 retried=0 failed=0 lostLease=0
Consumer: received=20 accepted=20 suppressed=0 deleted=20 failed=0
Queue: visible=0 inFlight=0 delayed=0; DLQ visible=0
Outbox: pending=0 publishing=0 retry=0 sent=20 failed=0 deferred=21
Database: processed=20 checkpointVersion=20 alerts=1
Final score: HOME 18 - AWAY 14; player_ace=13
Final-state checksum: 06d40d7e19ecf9ed9496e1523e6715bba029f008f94cb76148f600702d4c3bca
Drain complete before deadline: true
```

Run phases or inspection in separate processes:

```bash
./gradlew :apps:queue-replay-cli:run --args='--reset-import --publish'
./gradlew :apps:queue-replay-cli:run --args='--drain'
./gradlew :apps:queue-replay-cli:run --args='--inspect'
./gradlew :apps:queue-replay-cli:run --args='--run'
```

The last command demonstrates restart safety: no eligible game-event outbox work remains and the
durable result is unchanged.

## Read API

The API has three explicit boundaries: `modules/query` owns read models, cursor handling, data
status, and JDBC; `apps/api` owns Spring MVC, public DTOs, errors, OpenAPI, health, and runtime
composition; PostgreSQL remains the only source of served state. Controllers contain no SQL and no
persistence or queue records are serialized directly.

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/api/v1/games` | Stable game list; optional `status`, bounded `limit`, opaque `cursor` |
| GET | `/api/v1/games/{gameId}` | Current snapshot, recent events, checkpoint version, and ETag |
| GET | `/api/v1/games/{gameId}/events` | Ordered canonical history; `cursor` or `afterSequence` |
| GET | `/api/v1/games/{gameId}/alerts` | Ordered durable logical alerts |
| GET | `/api/v1/operations/processing` | Sanitized aggregate processing and outbox health |
| GET | `/api/v1/operations/rules` | Sanitized aggregate rule and private-alert counts |
| GET | `/api/v1/auth/config` | Public, non-secret browser OIDC configuration |
| GET | `/api/v1/me` | Authenticated user derived from the JWT subject |
| GET | `/api/v1/me/followed-games` | Authenticated user's followed games |
| PUT | `/api/v1/me/followed-games/{gameId}` | Idempotently follow a known game |
| DELETE | `/api/v1/me/followed-games/{gameId}` | Idempotently remove the user's follow |
| GET, POST | `/api/v1/me/rules` | Page or create the authenticated user's structured rules |
| GET, PATCH, DELETE | `/api/v1/me/rules/{ruleId}` | Read, version-update, or delete one owned rule |
| GET | `/api/v1/me/alerts` | Page immutable private alert history |

Game reads and `/ws/v1/games` are public. `/api/v1/me/**` requires a valid bearer token.
`/api/v1/operations/**` additionally requires the configured `courtpulse:ops` authority. User IDs
are never accepted as request input; ownership always comes from the validated `sub` claim.

The maximum page size is 100. Cursors are versioned, URL-safe Base64 JSON values scoped to the
resource and filter. Clients must treat them as opaque. Games order by checkpoint update time and
game ID, events by sequence/revision/event ID, and alerts by creation time/trigger key.

`dataStatus` is deliberately separate from basketball game status. A failed game-event outbox row
is `PROCESSING_BLOCKED`; otherwise scheduled and final games are `SCHEDULED` and `FINAL`; a live
checkpoint is `LIVE` inside the configured two-minute freshness window and `STALE` outside it.

Start infrastructure, seed through the real queue path, and run the API in a second terminal:

```bash
export COURTPULSE_DB_PASSWORD='courtpulse-local-dev'
docker compose up -d postgres localstack
./gradlew :apps:queue-replay-cli:run --args='--reset-import --run'
./gradlew :apps:api:run
```

Exercise every public endpoint with actual HTTP requests:

```bash
curl -sS http://localhost:8080/api/v1/games
curl -sS http://localhost:8080/api/v1/games/game_synthetic_001
curl -sS 'http://localhost:8080/api/v1/games/game_synthetic_001/events?limit=7'
curl -sS 'http://localhost:8080/api/v1/games/game_synthetic_001/events?afterSequence=17&limit=10'
curl -sS http://localhost:8080/api/v1/games/game_synthetic_001/alerts
curl -sS -H 'Authorization: Bearer PASTE_OPERATIONS_ACCESS_TOKEN' \
  http://localhost:8080/api/v1/operations/processing
curl -sS http://localhost:8080/actuator/health/liveness
curl -sS http://localhost:8080/actuator/health/readiness
```

Copy `nextCursor` from the first event page and pass it back URL-encoded:

```bash
curl -sS --get http://localhost:8080/api/v1/games/game_synthetic_001/events \
  --data-urlencode 'limit=7' \
  --data-urlencode 'cursor=PASTE_NEXT_CURSOR_HERE'
```

Capture the snapshot ETag and prove the body-free conditional response:

```bash
curl -si http://localhost:8080/api/v1/games/game_synthetic_001
curl -si -H 'If-None-Match: PASTE_ETAG_HERE' \
  http://localhost:8080/api/v1/games/game_synthetic_001
```

Conditional GET uses Spring's HTTP validator handling, so exact, weak, wildcard, and comma-separated
`If-None-Match` values follow HTTP weak-comparison semantics for GET. A stale validator returns a
fresh 200 representation and ETag.

An unknown game returns `application/problem+json` with a stable code and correlation ID:

```bash
curl -si http://localhost:8080/api/v1/games/missing-game
```

Expected seeded values are score 18–14, `player_ace=13`, checkpoint version 20, 20 canonical
events, one logical alert, 20 sent game-event rows, 21 deferred-notification rows, and checksum
`06d40d7e19ecf9ed9496e1523e6715bba029f008f94cb76148f600702d4c3bca`.

OpenAPI is published at `/v3/api-docs`; the reviewable OpenAPI 3.1 contract is
`contracts/openapi/courtpulse-v1.yaml`. An integration test compares paths, methods, parameters,
bounds and enums, required flags, response codes and media types, headers, and normalized public
schemas with the runtime document. Reproducible natural and index-eligibility plans are recorded in
[`docs/verification/milestone-4-query-plans.md`](docs/verification/milestone-4-query-plans.md).

### Health, diagnostics, and request safety

The public security policy allows only `/actuator/health` and its liveness/readiness probes;
`/actuator/metrics` requires the operations authority and other actuator routes are denied.
Liveness is independent of
PostgreSQL. Readiness requires a database connection and the expected schema. Health details,
environment variables, bean listings, configuration properties, and heap dumps are not exposed.

Responses echo a valid `X-Correlation-ID` or generate one. Request logs contain correlation ID,
method, normalized route, response status, and elapsed time—not query strings, credentials,
authorization headers, cookies, or provider payloads. Errors use RFC Problem Details. The narrow
processing endpoint intentionally omits outbox error text and payloads and requires the configured
operations authority.

### API container and Compose

The API Dockerfile uses pinned Gradle/JDK 21 and JRE 21 Alpine stages, copies only the Boot JAR to
the final image, and runs as a non-root user. With the fixture already seeded, stop any host API on
port 8080 and run:

```bash
export COURTPULSE_DB_PASSWORD='courtpulse-local-dev'
docker compose config
docker compose build api
docker compose up -d postgres api
docker compose ps
curl -fsS http://localhost:8080/actuator/health/readiness
curl -fsS http://localhost:8080/api/v1/games/game_synthetic_001
```

The `api` service depends only on healthy PostgreSQL; LocalStack may remain stopped for read-only
serving. Stop all services without deleting durable volumes:

```bash
docker compose down
```

Configuration defaults are in `apps/queue-replay-cli/src/main/resources/application.yml`.
Overrides include `COURTPULSE_DB_URL`, `COURTPULSE_DB_USERNAME`, `COURTPULSE_DB_PASSWORD`,
`COURTPULSE_SQS_ENDPOINT`, `AWS_REGION`, queue names, batch sizes, lease/retry durations,
`COURTPULSE_CONSUMER_WORKERS` (1–8), and `COURTPULSE_DRAIN_TIMEOUT`.

## Web dashboard

`apps/web` is a React 19 and TypeScript single-page application built by Vite. TanStack Query owns
bounded REST caching, retry, pagination, and refresh policy; React Router owns `/` and
`/games/{gameId}`. Public request and response types come from the checked OpenAPI contract rather
than handwritten mirrors. The browser never computes an authoritative score, player total, event,
or alert: PostgreSQL-backed REST responses remain the source of truth.

Run the API on port 8080 and the Vite development server on port 5173:

```bash
cd apps/web
nvm use
npm ci
npm run dev
```

Vite proxies `/api` and health requests to the local API. The production container uses unprivileged
Nginx on port 8080, serves the immutable hashed bundle, falls back to `index.html` for deep routes,
and proxies `/api/v1/...` to the API service. It exposes only API liveness and readiness beneath
`/actuator`; metrics and other actuator routes are not available through the public web boundary.
Neither development nor production requires a backend URL in the browser bundle.

Frontend validation commands are:

```bash
cd apps/web
npm run check:api
npm run typecheck
npm run lint
npm run test:run
npm run build
npm run e2e
```

Vitest runs only `src/**/*.test.ts` and `src/**/*.test.tsx`; Playwright exclusively owns `e2e/`.
The Playwright configuration runs desktop Chromium and a Pixel 7 viewport against port 4173. Start
the production-shaped stack without resetting its durable volume:

```bash
export COURTPULSE_DB_PASSWORD='courtpulse-local-dev'
docker compose config
docker compose build web
docker compose up -d postgres localstack api web
docker compose ps
curl -fsS http://localhost:4173/
curl -fsS http://localhost:4173/games/game_synthetic_001
curl -fsS http://localhost:4173/api/v1/games/game_synthetic_001
```

The safe seeded-game acceptance flow assumes the existing durable fixture and never runs
`--reset-import`: open `/`, select the final game, load possession pages until 20 unique rows are
shown, and verify HOME 18–AWAY 14, `player_ace` at 13 points, checkpoint v20, and exactly one
milestone alert. Use Testcontainers or a separately named Compose project and isolated volumes when
a clean database is required.

Snapshot requests retain the last ETag and send `If-None-Match`; a 304 preserves the cached
representation. Fresh live games poll every 15 seconds, stale or processing-blocked games every 30
seconds, other non-final games every 60 seconds, and final games stop automatic polling. TanStack
Query pauses interval work while the document is hidden and refreshes stale data when focus returns.
Manual refresh remains available.

## Realtime hints and paced replay

The versioned protocol is checked in at
`contracts/asyncapi/courtpulse-realtime-v1.yaml` and served at `/ws/v1/games`. A client subscribes to
one game with its last observed state version. The server acknowledges the subscription and emits
`GAME_STATE_UPDATED`, `ALERT_CREATED`, `RESYNC_REQUIRED`, or sanitized `PROBLEM` messages. Every hint
has a schema version, stable message ID, game ID, emission time, correlation ID, and state version
where applicable.

Hints are never authoritative. The API claims committed `FUTURE_NOTIFICATIONS` outbox rows in short
transactions, broadcasts after the claim commits, and marks completion in another short transaction.
Expired claims are recoverable. A crash after broadcast but before completion can redeliver the same
message ID; the browser suppresses it. A missing or gapped version invalidates the snapshot, event,
and alert queries so the browser resynchronizes from HTTP/PostgreSQL. Healthy sockets suppress
redundant interval polling; disconnects retain the existing polling policy while bounded exponential
backoff reconnects. Hidden documents pause reconnect work until visible.

Run the deterministic paced replay and reconnect acceptance harness. It uses the fixed
`courtpulse-m6-acceptance` Compose project, separate volumes and host ports, waits on observable
health/checkpoint state, captures failure diagnostics under `build/verification/milestone-6`, and
never touches the normal `courtpulse` database:

```bash
./scripts/verify-milestone-6.sh
```

The harness proves 20 visible messages before draining, starts both desktop and mobile browsers at
an early checkpoint during a 1000 ms single-event replay, records actual WebSocket frames, forces a
same-identity alert redelivery, and interrupts only the isolated API while a browser remains open.
The expected durable result remains HOME 18–AWAY 14, `player_ace=13`, 20 ordered events, one alert,
and checksum
`06d40d7e19ecf9ed9496e1523e6715bba029f008f94cb76148f600702d4c3bca`. Remove only the explicitly
named isolated project manually only if a terminated harness did not reach its cleanup trap:

```bash
docker compose -p courtpulse-m6-acceptance down -v
```

Realtime metrics cover active sessions/subscriptions, published and stale hints, resync requests,
slow-client disconnects, publication failures, and recovered leases. Logs carry message ID, game ID,
state version, and type without tokens or raw payloads. For failure injection, the publisher exposes
an automated crash-after-broadcast mode; persistence tests prove the expired lease republishes the
same identity and ownership prevents stale completion.

The hub is intentionally single-API-instance. Before horizontal API scaling, add Redis or another
shared fanout layer while retaining PostgreSQL outbox ownership and HTTP resynchronization.

## OIDC authentication and owned games

The browser uses OAuth 2.0 Authorization Code with PKCE through `oidc-client-ts`. It is a public
client with no client secret. OIDC state, the code verifier, and the resulting user session use
`sessionStorage`, not local storage; tokens are sent only in the `Authorization` header to protected
same-origin API routes. They are never placed in URLs, rendered into HTML, or logged. The API is an
independent Spring Security resource server and validates signature, issuer, audience, expiration,
not-before, bounded nonblank `sub`, and configurable scope/role claims before trusting ownership.
The callback waits for OIDC configuration and runs once under React Strict Mode. Discovery, redirect,
callback, logout, and expiry failures clear private query data and leave public browsing available
with a sanitized retry message.

The production Nginx CSP keeps `default-src`, scripts, styles, fonts, images, frames, and base URIs
narrow. `connect-src` contains only `'self'` plus the validated browser-visible issuer origin. Empty
configuration leaves only `'self'`; non-local origins must use HTTPS, while HTTP is accepted only for
`localhost` or `127.0.0.1`. Container startup rejects paths, user information, malformed ports, and
other unsafe substitutions. This origin permits browser discovery and token calls but does not reveal
the server-only JWKS address. No wildcard, `unsafe-inline`, or `unsafe-eval` source is used.

| Route class | Policy |
| --- | --- |
| `/api/v1/auth/config`, `/api/v1/games/**`, `/ws/v1/games` | Public |
| `/actuator/health/**`, `/v3/api-docs/**` | Public intentionally |
| `/api/v1/me`, `/api/v1/me/**` | Valid bearer token |
| `/api/v1/operations/**`, `/actuator/metrics/**` | Valid bearer token plus configured operations authority |
| Other actuator, unclassified API, or accidental controller routes | Denied by default |

An anonymous request to an unclassified protected route receives sanitized 401; an authenticated
identity without a matching rule receives sanitized 403. MVC errors for explicitly public routes
still reach safe 404/405 Problem Details through permitted error dispatch. The API is stateless,
disables server sessions and CSRF for bearer authentication, and rejects bearer tokens in query or
form parameters. Authentication-disabled mode does not make protected resources public.

The optional local provider is Keycloak 26.7.0. Copy `.env.example` to an ignored `.env`, choose
local-only values for every blank variable, then start and provision the profile:

```bash
docker compose --profile auth up -d identity postgres localstack api web
./scripts/provision-local-identity.sh
```

The provisioning script reads two ordinary test identities, one operational identity, and all
passwords from the environment; no account password or admin credential is committed. The imported
realm contains only public client, redirect-origin, audience, PKCE, and role-claim configuration.
Use `http://127.0.0.1:4173`; mixing `localhost` and `127.0.0.1` changes browser origin and redirect
matching. The API's external issuer and internal JWKS URI are separately configurable so the same
validation model maps to Cognito or another standards-compliant provider without code changes.
Set `COURTPULSE_AUTH_ISSUER_ORIGIN` to the origin portion of the external issuer for the browser CSP;
set `COURTPULSE_AUTH_ISSUER_URI` to the complete externally visible issuer and optionally
`COURTPULSE_AUTH_JWK_SET_URI` to a container-network address used only by the API. Enabled
authentication fails startup unless issuer, audience, public client ID, an `openid` browser scope,
authority claim/prefix, and operations authority are valid.

Sign-in returns through `/auth/callback`. Anonymous users can keep browsing games. Authenticated
users can follow/unfollow from game detail and open **My Games**. Expired sessions become a
recoverable sign-in state; 401 means the session is absent/invalid, while 403 means the identity is
valid but lacks authority. Sign-out removes authenticated UI access while public game data remains.
Follows are unique by subject/game and all database work is transactional. The minimal user row stores
only `sub` and timestamps; ordinary reads update `last_seen_at` at most once per 15-minute window.

Run the isolated proof with generated in-memory credentials and isolated ports/volumes:

```bash
./scripts/verify-milestone-7.sh
```

It proves S256 PKCE with no browser secret, two-user ownership isolation, ordinary-user 403,
operations-user access, logout/public access, narrow CSP on HTML and assets, non-root images, exact
`3|1` user/follow counts, and scoped cleanup of only `courtpulse-m7-acceptance`.

Troubleshooting:

- Provider unhealthy: inspect `docker compose --profile auth logs identity` and verify the admin
  variables are non-empty before startup.
- Login redirect rejected: confirm the browser origin is one of the two local realm origins.
- Sign-in does not leave CourtPulse: inspect the browser console for CSP failures and ensure
  `COURTPULSE_AUTH_ISSUER_ORIGIN` exactly matches the external issuer origin.
- API returns 401 for a fresh token: verify issuer, internal JWKS URI, audience, and container DNS.
- Operations return 403: only the identity provisioned with `COURTPULSE_TEST_OPS_USER` receives the
  local `courtpulse:ops` role.
- This local profile uses HTTP for development. Production requires HTTPS, hardened provider
  storage and keys, restrictive origins, rotation, monitoring, and an explicit session policy.
- `sessionStorage` limits persistence to the tab but does not eliminate XSS or token-theft risk;
  short token lifetimes, dependency review, CSP monitoring, and provider-side session controls remain
  deployment responsibilities. Silent renewal and refresh-token persistence are intentionally absent.
- The public game WebSocket carries no user data and remains anonymous. A future user-specific
  realtime destination must authenticate during the WebSocket handshake without query tokens.

## Personalized alert rules

CourtPulse supports exactly three structured templates. There is no expression, SQL, script, or
arbitrary-predicate input.

- `PLAYER_POINTS` fires once when a verified player total crosses from below the configured 1–200
  threshold to at-or-above it. Remaining above does not repeat the alert.
- `CLOSE_GAME` fires on entry into a live window: the period must equal the configured period, the
  remaining clock must be at or below the configured value, and the absolute margin must be at or
  below the configured value. Exit is silent and a later re-entry creates a distinct alert.
- `SCORING_RUN` fires when a team's unanswered points cross the configured 1–100 threshold. Made
  field goals and free throws count, non-scoring events preserve a run, and an opponent score ends
  it. A later run has a new start-sequence identity.

All evaluations use committed canonical events and verified previous/next game states. Alert context
is an immutable snapshot, so later rule changes cannot alter its explanation. The event transaction
atomically commits its checkpoint, processed identity, private alert, and any public system hint.
Database uniqueness suppresses both duplicate event delivery and duplicate logical trigger keys.
Personalized alerts create no public WebSocket outbox row, and public game alert reads return only
null-owner system alerts.

Create a player rule with an access token and a new safe idempotency key:

```bash
curl -si -X POST http://localhost:8080/api/v1/me/rules \
  -H 'Authorization: Bearer PASTE_ACCESS_TOKEN' \
  -H 'Idempotency-Key: demo-player-10' \
  -H 'Content-Type: application/json' \
  --data '{"type":"PLAYER_POINTS","gameId":"game_synthetic_001","playerId":"player_ace","pointsThreshold":10}'
```

An identical retry returns the existing resource. Reusing the key for another normalized request
returns 409, including after the original rule is toggled. The browser retains the same key after
an uncertain network failure. Every user may own at most 50 rules, and a game may have at most
1,000 owned rules across all users; overflow returns 429. PATCH requires both the new `enabled`
value and the version returned by the
last read; a stale version returns 409:

```bash
curl -sS -X PATCH http://localhost:8080/api/v1/me/rules/PASTE_RULE_UUID \
  -H 'Authorization: Bearer PASTE_ACCESS_TOKEN' \
  -H 'Content-Type: application/json' \
  --data '{"enabled":false,"version":1}'
curl -sS -H 'Authorization: Bearer PASTE_ACCESS_TOKEN' \
  'http://localhost:8080/api/v1/me/alerts?limit=20'
```

All rule and private-alert reads use stable owner-scoped keyset order, opaque cursors, and a maximum
page size of 100. Ownership comes only from JWT `sub`; a foreign UUID is indistinguishable from an
absent one. Logout, expiration, and identity replacement clear all `me`-scoped browser caches.

Scoring-run state is one durable row per game, advanced on every made score inside the event
transaction and reconstructed once for pre-V6 processed games. The system demo rule has the same
stored ID from fresh fixture import and upgrade backfill, while its historical logical trigger
identity remains `milestone-player-ace-10`.

The protected `/api/v1/operations/rules` endpoint reports aggregate enabled/disabled/system rule and
private-alert counts without subjects or parameters. Micrometer records bounded lookup, loaded,
disabled, evaluated, match, create, duplicate, quota, and duration metrics; only rule type and fixed
outcome/reason values are tags. Reproducible index evidence is in
[`docs/verification/milestone-8-query-plans.md`](docs/verification/milestone-8-query-plans.md).

The isolated authenticated demonstration provisions two users, creates every template, replays
through PostgreSQL and SQS, forces consumer redelivery, checks browser ownership and public-channel
privacy, validates production images, and removes only its named resources:

```bash
./scripts/verify-milestone-8.sh
```

## Local alert delivery

Authenticated users can opt into local email on **Notification settings** and inspect delivery
status, attempt counts, safe errors, and retry times on **My alerts**. The in-app delivery is
created for every private alert. Email is created only if the user had an enabled local destination
when that alert was committed. Changing destination or opting out cancels pending work; an already
leased send may finish at the original snapshot address. Public game reads and WebSocket frames do
not include private deliveries.

Start the bounded automatic publisher/worker and local SMTP sink with:

```bash
docker compose --profile auth --profile delivery up -d --build
docker compose --profile delivery ps delivery-worker mailpit
```

Mailpit is at `http://localhost:8025` by default. The worker has a heartbeat healthcheck, restarts
on failure, and stops on SIGTERM. For a deterministic one-shot drain, use
`./gradlew :apps:queue-replay-cli:run --args='--delivery-run'`; it intentionally does not wait for
future retries, while the daemon does. Only local Mailpit hosts and ports are accepted. No external
email service is contacted.

An owner can read `GET /api/v1/me/notifications/deliveries?limit=20` and
`GET /api/v1/me/notifications/deliveries/{deliveryId}/attempts?limit=20` with their bearer token;
pages use opaque `nextCursor` values. Operators with the `courtpulse:ops` authority can read
`GET /api/v1/operations/deliveries` for aggregate backlog, age, publication, attempt, success,
retry, failure, lease-recovery, and last-observed DLQ counts. The DLQ observation timestamp
indicates freshness. Metrics use aggregate names without private tags.

Inspect local poison messages without clearing other queues:

```bash
docker compose --profile delivery exec localstack awslocal sqs get-queue-url --queue-name alert-deliveries-dlq.fifo
docker compose --profile delivery exec localstack awslocal sqs receive-message --queue-url http://localhost:4566/000000000000/alert-deliveries-dlq.fifo
```

Fix the cause and assess whether SMTP may already have accepted the message before replaying a DLQ
entry. Retryable failures back off with jitter for at most five claims; permanent/exhausted work is
terminal. External email is **at least once**: a crash after SMTP acceptance but before the
database acknowledgement can produce a duplicate email. See [ADR 0009](docs/adr/0009-reliable-alert-delivery.md).
The isolated real-Keycloak/PostgreSQL/LocalStack/Mailpit acceptance is
`./scripts/verify-milestone-9.sh`; it removes only its named `courtpulse-m9-acceptance` stack.

## Corrections and reconciliation

Flyway V8 preserves all raw and canonical revisions and records each per-game reconciliation
attempt and selected event/raw-payload provenance. New or late events park the forward processor
and request a rebuild. The worker chooses the highest valid revision per contiguous sequence and
replaces checkpoint, processed identities, scoring run, and alert validity in one transaction.
Gaps, unresolved same-revision conflicts, and histories with no valid candidate become `BLOCKED`;
the worker does not hot-retry them. Importing new evidence makes the game eligible again. A stale
`REBUILDING` claim is retried after five minutes. Raw conflicting observations remain in the
private audit table instead of overwriting the first accepted identity.

Run the local worker with:

```bash
docker compose --profile reconciliation up -d --build reconciliation-worker
```

For an explicit bounded pass or a fixture correction, use:

```bash
./gradlew :apps:queue-replay-cli:run --args='--reconciliation-run'
./gradlew :apps:queue-replay-cli:run --args='--correction-fixture=fixtures/corrections/ace-to-home-2.json'
```

The public game snapshot ETag includes its checksum, and selected event history hides superseded
revisions. A successful changed rebuild emits a public `RESYNC_REQUIRED` WebSocket hint with the
fixed `game_correction` reason; connected clients reload HTTP state even if the play count is
unchanged. Owner-only alerts may become `CORRECTED`. Sent emails and attempt history remain; only
pending/retry-scheduled email work is cancelled. New logical alerts get the normal unique delivery
intent. Operators with `courtpulse:ops` can read aggregate
`GET /api/v1/operations/reconciliations`; it includes no owner or private rule data.

Use [the reconciliation runbook](docs/runbooks/reconciliation.md) for blocked work. The isolated
real-PostgreSQL/Keycloak/LocalStack/Mailpit/browser proof is
`./scripts/verify-milestone-10.sh`; it creates a uniquely named Compose project and removes only
that project's volumes. See [ADR 0010](docs/adr/0010-corrections-and-reconciliation.md).

## Failure and redelivery demonstrations

SQS accepted the send, then the publisher failed before recording `SENT`:

```bash
./gradlew :apps:queue-replay-cli:run --args='--reset-import --publish --simulate-publisher-after-send'
sleep 31
./gradlew :apps:queue-replay-cli:run --args='--publish --drain'
```

Consumer rollback before commit, then redelivery after visibility expires:

```bash
./gradlew :apps:queue-replay-cli:run --args='--reset-import --publish'
./gradlew :apps:queue-replay-cli:run --args='--drain --simulate-consumer-before-commit'
sleep 9
./gradlew :apps:queue-replay-cli:run --args='--drain'
```

Consumer commit followed by failure before delete; the next process suppresses the duplicate:

```bash
./gradlew :apps:queue-replay-cli:run --args='--reset-import --publish'
./gradlew :apps:queue-replay-cli:run --args='--drain --simulate-consumer-after-commit'
sleep 9
./gradlew :apps:queue-replay-cli:run --args='--drain'
```

Demonstrate poison-message redrive by sending malformed JSON. The bounded drain keeps long-polling
through the visibility intervals and exits after LocalStack redrives the third failed receive:

```bash
docker compose exec localstack awslocal sqs send-message \
  --queue-url http://sqs.us-east-1.localhost.localstack.cloud:4566/000000000000/game-events.fifo \
  --message-body '{not-json' --message-group-id poison-game \
  --message-deduplication-id poison-1
./gradlew :apps:queue-replay-cli:run --args='--drain'
```

Inspect queue depth and receive the DLQ message without deleting it:

```bash
docker compose exec localstack awslocal sqs get-queue-attributes \
  --queue-url http://sqs.us-east-1.localhost.localstack.cloud:4566/000000000000/game-events.fifo \
  --attribute-names ApproximateNumberOfMessages ApproximateNumberOfMessagesNotVisible
docker compose exec localstack awslocal sqs receive-message \
  --queue-url http://sqs.us-east-1.localhost.localstack.cloud:4566/000000000000/game-events-dlq.fifo
```

## Correctness boundaries

Flyway V2 adds explicit destination, message group, lease owner/expiration, and bounded last-error
fields without modifying V1. The state machine is `PENDING -> PUBLISHING -> SENT`,
`PUBLISHING -> RETRY_SCHEDULED -> PUBLISHING`, or `PUBLISHING -> FAILED`.

A short transaction claims eligible `GAME_EVENTS` rows with `FOR UPDATE SKIP LOCKED`, records the
owner and expiration, and commits before SQS is called. A second short transaction completes the row
only while the same publisher owns a still-valid lease. Expired rows are reclaimable.

Within one game, every older non-`SENT` row—including `FAILED`—blocks a newer row. Order is sequence,
revision, creation time, then outbox UUID. The V1 unique `(game_id, sequence_number, revision)`
constraint rejects ambiguous equal sequence/revision records. Different game groups remain
independently claimable.

The version-1 envelope carries type/schema, event/game/sequence, provider identity, occurrence time,
outbox ID, stable deduplication key, and optional correlation ID. Before processing, the consumer
compares this data with the committed canonical event and outbox row. It invokes the existing
durable processor and deletes only after its transaction commits. PostgreSQL processed identities
and alert trigger keys—not FIFO deduplication—remain authoritative.

`GAME_STATE_UPDATED` and `ALERT_CREATED` use `FUTURE_NOTIFICATIONS`. A separate leased publisher
claims those rows only after the processor commits and emits versioned WebSocket hints. The
game-events publisher continues to claim only `GAME_EVENTS` rows.

The version-2 state checksum covers game/team IDs, status, period, clock, score, last sequence,
sorted player totals, every accepted provider identity plus canonical fingerprint, and bounded
recent-event history. Operational times and delivery counters are excluded.

## Modules

```text
modules/domain       pure events, reducer, rules, checksum (no infrastructure)
modules/providers    fixture mapping and raw source evidence
modules/persistence  Flyway, JDBC, checkpoints, idempotency, outbox leases
modules/messaging    queue port, publisher/consumer, AWS SDK v2 SQS adapter
modules/testkit      reusable synthetic fixture
modules/query        read models, keyset cursors, data status, bounded JDBC
apps/replay-cli      infrastructure-free replay
apps/durable-replay-cli  direct PostgreSQL replay
apps/queue-replay-cli    bounded PostgreSQL -> FIFO SQS -> PostgreSQL demo
apps/api             Spring MVC read API, DTOs, OpenAPI, errors, health
apps/web             React dashboard, typed REST client, Nginx same-origin boundary
```

## Troubleshooting and shutdown

- Docker unavailable: start Docker Desktop and ensure `docker info` succeeds for the same user.
- PostgreSQL authentication failure: use the password that initialized this Compose volume.
- LocalStack unhealthy: inspect `docker compose logs localstack`; SQS must show `running` and the
  ready hook must finish.
- Stale queue messages: use the explicit `--reset-import` local-demo action. SQS can reject repeated
  purge requests for 60 seconds.
- A row remains `PUBLISHING`: wait for lease expiry, then run `--publish`.
- An older `FAILED` row blocks its game by design; inspect and repair it rather than skipping ahead.
- API returns 503: verify PostgreSQL is healthy, the configured password matches the existing
  volume, and `/actuator/health/readiness` becomes `UP`.
- Port 8080 is busy: stop the host API or set `COURTPULSE_API_HOST_PORT` for Compose.
- Port 4173 is busy: set `COURTPULSE_WEB_HOST_PORT` for the Compose web service.
- Frontend types are stale: run `npm run generate:api` in `apps/web`, inspect the contract-driven
  change, then rerun `npm run check:api`.
- A cursor is rejected: treat it as opaque and do not reuse it for another resource, game, or
  filtered game list.

Stop without deleting data:

```bash
docker compose down
```

Intentionally delete only this Compose project's PostgreSQL and LocalStack volumes:

```bash
docker compose down -v
```

## Current limitations

- The synthetic fixture is the only provider; live provider refetch and overtime are deferred.
- LocalStack is test infrastructure, not a production AWS deployment.
- External email providers beyond local Mailpit remain deferred; the realtime publisher still
  handles only the WebSocket hint types documented in the AsyncAPI contract.
- Realtime fanout is single-instance and falls back to polling. There
  is no personalized realtime channel, offline mode, external email provider, live provider integration,
  Redis multi-replica fanout, Terraform, or Kubernetes. Rules cannot target overtime because the
  current close-game template deliberately bounds eligible periods to 1–4.

See [ADR 0001](docs/adr/0001-infrastructure-independent-domain.md),
[ADR 0002](docs/adr/0002-postgresql-transactional-outbox.md),
[ADR 0003](docs/adr/0003-sqs-fifo-outbox-leasing.md),
[ADR 0004](docs/adr/0004-read-api-contract-and-query-model.md),
[ADR 0005](docs/adr/0005-react-dashboard-and-polling.md),
[ADR 0006](docs/adr/0006-websocket-hints-and-http-resynchronization.md),
[ADR 0007](docs/adr/0007-oidc-pkce-and-object-ownership.md), and
[ADR 0008](docs/adr/0008-structured-personalized-alert-rules.md), and
[ADR 0009](docs/adr/0009-reliable-alert-delivery.md), and
[ADR 0010](docs/adr/0010-corrections-and-reconciliation.md).
