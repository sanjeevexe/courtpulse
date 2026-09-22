# ADR 0007 Use OIDC PKCE and subject-derived object ownership

- Status: Accepted
- Date: 2026-09-21

## Context

CourtPulse needs user-owned preferences before personalized alert rules can be introduced. Public
basketball data should remain easy to browse, while user resources and operational state require
production-shaped authentication and authorization. Browser-delivered software cannot safely keep
a client secret, and CourtPulse must remain portable from a local provider to Amazon Cognito.

## Decision

Use OpenID Connect and OAuth 2.0 Authorization Code with S256 PKCE. The React browser is a public
client with no secret. `oidc-client-ts` owns protocol state and code-verifier generation, stores the
session in `sessionStorage`, and sends access tokens only as bearer headers to protected same-origin
HTTP endpoints. Tokens, authorization codes, raw claims, and refresh material are never logged or
rendered.

The Spring API validates JWTs independently as a resource server. Signature, configured issuer,
audience, expiration, and not-before are mandatory. Authority claim name, prefix, and the operations
authority are configurable. Enabling authentication with a missing issuer or audience fails startup;
unsigned or invalidly signed tokens fail closed. Sanitized 401 and 403 Problem Details retain the
request correlation ID without echoing credentials or decoder exceptions.

The browser must contact the external issuer for discovery and the authorization-code token exchange.
Nginx therefore renders `connect-src 'self' <issuer-origin>` from one validated runtime origin. Empty
configuration produces a self-only policy; non-loopback origins require HTTPS. The entrypoint rejects
paths, user information, malformed ports, and unsafe source text before Nginx starts. All other CSP
directives remain self-only (with `data:` limited to images), with no wildcard, inline script, or eval
allowance. The internal JWKS URL is API-only and is never included in browser configuration or CSP.

Public game slate, snapshot, event, alert, and game WebSocket routes remain anonymous because they
contain only public game information. `/api/v1/me/**` requires authentication.
`/api/v1/operations/**` requires the dedicated `courtpulse:ops` authority. The public WebSocket does
not accept bearer tokens in its query string. User-specific WebSocket delivery is deferred until a
handshake-authenticated design exists.

Route authorization is enumerated and then denied by default. Public auth configuration, game reads,
game WebSocket hints, health probes, and OpenAPI are explicit exceptions; unclassified API routes,
other actuator routes, and accidental controllers fail closed. Error dispatch is allowed so public
MVC failures retain safe Problem Details. The API uses no server session, disables CSRF for the
stateless bearer boundary, and rejects query/form bearer tokens. Disabled authentication retains the
same protected-route policy with a decoder that rejects every bearer token.

Flyway V5 stores a minimal application user keyed by the stable OIDC `sub` and a unique
`(user_subject, game_id)` follow relation. Email and profile claims are not persisted. Controllers
never accept a user identifier; every read and mutation derives ownership from the validated JWT
subject. Repeated follow and unfollow operations are idempotent, and database foreign keys reject
unknown games and prevent orphaned ownership rows.
JWT validation rejects blank or overlong subjects before ownership use. The persistence boundary
repeats that check defensively. User observation writes are throttled to one `last_seen_at` update per
15-minute window so ordinary reads cannot cause unbounded write amplification.

The optional Compose auth profile uses a pinned Keycloak image with reproducible realm, public PKCE
client, audience, and role-claim configuration. Test users and all passwords are provisioned from
environment variables rather than committed. The API separates external issuer validation from an
optional internal JWKS endpoint, which accommodates container networking. Cognito substitution is
configuration: issuer, JWKS discovery, audience/client ID, claim name, and authority prefix change;
the application ownership and authorization code do not.

Callback processing waits until the `UserManager` exists and is single-shot under React Strict Mode.
Discovery/redirect and callback failures are sanitized while the public application stays usable.
Logout and expiration remove local identity plus all `me`-scoped query data even if the provider is
unavailable. Exact post-logout redirect URIs are registered for local origins.

## Consequences

- Public browsing and public realtime hints keep their existing availability characteristics.
- User-owned follows are isolated at both the HTTP and database query boundaries.
- Operations are no longer anonymously visible.
- The browser has no client secret and a stolen authorization code is insufficient without the PKCE
  verifier.
- Session storage limits persistence but does not eliminate XSS risk; CSP, dependency hygiene,
  HTTPS, provider hardening, and token lifetime policy remain production responsibilities.
- Personalized rule evaluation, email delivery, and authenticated user-specific realtime channels
  remain future milestones. The next milestone is the personalized rule engine.
