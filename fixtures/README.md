# Fixture data provenance

`synthetic-milestone-game.json` was authored specifically for CourtPulse. The teams, players,
game, events, identifiers, scores, timestamps, and ordering are fictional. It contains no copied
provider, league, broadcast, or third-party data and is safe to redistribute with this repository.

The canonical copy is packaged by `modules/testkit` so tests and the replay CLI use the exact same
fixture.

`modules/testkit/src/main/resources/fixtures/providers/balldontlie/synthetic-overtime-game.json` is
also fictional. It is generated deterministically by
`fixtures/providers/balldontlie/generate_synthetic_overtime_game.py` (fixed seed) in the response
shapes BALLDONTLIE documents for its v1 `games`, `plays`, and `players` endpoints, reviewed on
2026-09-28. It contains no provider data: two invented teams, ten invented players, 167 plays that
tie regulation and finish 79-77 in overtime, and one scorer correction. The adapter tests, the
provider simulator, and `scripts/verify-milestone-12.sh` all use it.

Real provider responses are licensed data. Do not commit them here. Review the provider's terms
before storing, caching, or displaying real games; see ADR 0013.
