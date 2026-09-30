# ADR 0015: Replay real completed games as live games

- Status: Accepted
- Date: 2026-09-30

## Context

Without a paid play-by-play subscription, CourtPulse could only be demonstrated on fictional data,
which made it hard to show that the system works on real basketball. The official NBA.com and
ESPN data endpoints refuse requests from this development network, so an unofficial live source
was not dependable either. The open nba_data project (https://github.com/shufinskiy/nba_data)
republishes NBA.com live-data play-by-play by season as compressed CSV on GitHub, including the
2025-26 regular season and the 2026 playoffs. Its code is Apache-2.0 licensed; the data comes from
NBA.com and carries no license of its own, so it is fetched on demand for personal,
non-commercial demonstration and never committed to the repository.

A validation pass over the 60 playoff games in the first download found every score change
explained by exactly one made shot, every game ending with a game-end action, and a home team
identifiable in every game. Actions must be ordered by the provider's `orderNumber`;
`actionNumber` skips values and is not monotonic after edits.

## Decision

**Replay is a provider.** `NbaReplayProvider` implements the same `LiveGameProvider` interface as
the BALLDONTLIE adapter and runs in the same ingest daemon (`COURTPULSE_PROVIDER=nba-replay`, the
`replay-worker` Compose service, polling every 2 s). Each replay session is an ordinary provider
game (`nba-replay-<nbaGameId>-<run>`), so replays exercise ingestion, the outbox, SQS, the
processor, alert rules, email, WebSockets, and freshness exactly as a live feed would. Every run
of a game is a separate CourtPulse game, so a game can be replayed again without deleting data.

**Import once, replay many times.** `--replay-import=<dataset|file>` downloads a named dataset
(only `cdnnba[_po]_20YY` names from that repository, HTTPS only, size-capped) or reads a local
file, validates each game, and stores it in `replay_catalog` and `replay_actions`. Games that
fail validation are skipped and counted, never partially imported. Player names go to
`replay_players` and reach the normal `players` table through the provider's player lookup.

**Replay time is data, not a timer.** A session stores `elapsed_ms`, `anchor_at`, `speed`, and
`status`; replay time at instant *t* is `elapsed_ms + (t - anchor_at) × speed` while running.
Pausing, resuming, changing speed, and skipping to the end fold the running segment into
`elapsed_ms`, so any process can compute the same replay time and changes survive restarts. Real
timestamps set the pace, except that gaps longer than 45 s (halftime, reviews) are shortened.

**Mapping follows the canonical rules.** The first action starts the game, later period starts
start periods, made shots and free throws score for the listed team and player, the game-end
action finishes the game, and everything else is a recorded play with its description. Event
timestamps are the real ones. Unknown teams are rejected with a fixed code, never guessed.

**Access.** The catalog and the list of replays are public. Starting and controlling replays
needs sign-in; only the fan who started a replay (or an operator) can pause, resume, change speed,
or skip it. Each fan may run 3 replays at a time and the server 10, enforced under an advisory
lock.

**Readable names everywhere.** Alerts, rules, and emails previously showed IDs
(`bdl-player-9000203 reached 16 points`). Alert text is now rendered when shown or sent, from the
alert's verified context and current display names, because names can arrive after an alert
fires. Stored titles are unchanged and remain the fallback.

## Consequences

- CourtPulse can be demonstrated on real NBA games at no cost, controlled from the browser.
- Replays are indistinguishable from live games downstream, so they also serve as realistic
  end-to-end tests (`scripts/verify-replay.sh`, with `REAL_DATA=1` for downloaded games).
- The dataset is an unofficial mirror: availability, completeness, and terms are outside
  CourtPulse's control, and replays must not be presented as official or live data. The UI says so.
- Replays cover only completed games; live games still need the BALLDONTLIE adapter.
- A regular-season import holds about 1,230 games; the database grows accordingly
  (roughly 150 KB per game).
