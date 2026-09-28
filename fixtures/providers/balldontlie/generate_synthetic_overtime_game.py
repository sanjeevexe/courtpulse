#!/usr/bin/env python3
"""Deterministically generates synthetic-overtime-game.json.

Every team, player, play, and timestamp is fictional and authored for CourtPulse. The JSON uses
the publicly documented BALLDONTLIE v1 `games`, `plays`, and `players` shapes so the adapter and
the local provider simulator can be exercised without a paid play-by-play subscription.
Re-run from the repository root with
`python3 fixtures/providers/balldontlie/generate_synthetic_overtime_game.py \
  > modules/testkit/src/main/resources/fixtures/providers/balldontlie/synthetic-overtime-game.json`.
"""
import json
import random
from datetime import datetime, timedelta, timezone

SEED = 20260928
GAME_ID = 990001
START = datetime(2026, 1, 15, 0, 10, tzinfo=timezone.utc)

HOME = {"id": 90001, "conference": "East", "division": "Atlantic", "city": "Harbor City",
        "name": "Herons", "full_name": "Harbor City Herons", "abbreviation": "HCH"}
AWAY = {"id": 90002, "conference": "West", "division": "Pacific", "city": "Summit Valley",
        "name": "Sentinels", "full_name": "Summit Valley Sentinels", "abbreviation": "SVS"}

HOME_PLAYERS = [(9000101, "Ada", "Lane"), (9000102, "Bo", "Okafor"), (9000103, "Cy", "Marsh"),
                (9000104, "Dee", "Vance"), (9000105, "Eli", "Brook")]
AWAY_PLAYERS = [(9000201, "Fin", "Castor"), (9000202, "Gus", "Ember"), (9000203, "Hal", "Quill"),
                (9000204, "Ivo", "Stone"), (9000205, "Jay", "Wren")]

rng = random.Random(SEED)
plays = []
score = {"home": 0, "away": 0}
wall = START


def name(player):
    return f"{player[1]} {player[2]}"


def clock_text(millis):
    seconds = millis // 1000
    return f"{seconds // 60}:{seconds % 60:02d}"


def period_display(period):
    return ["", "1st Quarter", "2nd Quarter", "3rd Quarter", "4th Quarter"][period] \
        if period <= 4 else f"OT{period - 4}" if period > 5 else "OT"


def add(period, clock_millis, kind, text, team=None, participants=(), points=None):
    global wall
    scoring = points is not None and points > 0
    if scoring:
        score["home" if team is HOME else "away"] += points
    wall += timedelta(seconds=rng.randint(35, 70))
    plays.append({
        "game_id": GAME_ID,
        "order": len(plays) + 1,
        "type": kind,
        "text": text,
        "home_score": score["home"],
        "away_score": score["away"],
        "period": period,
        "period_display": period_display(period),
        "clock": clock_text(clock_millis),
        "scoring_play": scoring,
        "shooting_play": kind.endswith("Shot") or kind.startswith("Free Throw"),
        "score_value": points if kind.endswith("Shot") or kind.startswith("Free Throw") else None,
        "team": team,
        "coordinate_x": None,
        "coordinate_y": None,
        "wallclock": wall.strftime("%Y-%m-%dT%H:%M:%S.000Z"),
        "participants": list(participants),
    })


def possession(period, clock_millis, team, roster, opponent, opponent_roster):
    shooter = rng.choice(roster)
    roll = rng.random()
    if roll < 0.30:
        add(period, clock_millis, "Jump Shot", f"{name(shooter)} makes 17-foot jumper",
            team, [shooter[0]], 2)
    elif roll < 0.45:
        add(period, clock_millis, "3-Point Jump Shot", f"{name(shooter)} makes 25-foot three point jumper",
            team, [shooter[0]], 3)
    elif roll < 0.55:
        add(period, clock_millis, "Driving Layup Shot", f"{name(shooter)} makes driving layup",
            team, [shooter[0]], 2)
    elif roll < 0.63:
        fouler = rng.choice(opponent_roster)
        add(period, clock_millis, "Shooting Foul", f"{name(fouler)} shooting foul", opponent, [fouler[0]])
        for attempt in (1, 2):
            made = rng.random() < 0.78
            add(period, clock_millis, f"Free Throw - {attempt} of 2",
                f"{name(shooter)} {'makes' if made else 'misses'} free throw {attempt} of 2",
                team, [shooter[0]], 1 if made else 0)
    elif roll < 0.85:
        add(period, clock_millis, "Jump Shot", f"{name(shooter)} misses 21-foot jumper",
            team, [shooter[0]], 0)
        rebounder = rng.choice(opponent_roster)
        add(period, clock_millis, "Defensive Rebound", f"{name(rebounder)} defensive rebound",
            opponent, [rebounder[0]])
    elif roll < 0.95:
        add(period, clock_millis, "Bad Pass Turnover", f"{name(shooter)} bad pass (turnover)",
            team, [shooter[0]])
    else:
        add(period, clock_millis, "Full Timeout", f"{team['full_name']} Full timeout", team)


def play_period(period, possessions, length_millis):
    step = length_millis // (possessions + 1)
    for index in range(possessions):
        clock_millis = length_millis - step * (index + 1)
        if index % 2 == 0:
            possession(period, clock_millis, HOME, HOME_PLAYERS, AWAY, AWAY_PLAYERS)
        else:
            possession(period, clock_millis, AWAY, AWAY_PLAYERS, HOME, HOME_PLAYERS)


# Tip-off: order 1 always starts the game.
add(1, 720_000, "Jumpball", f"{name(HOME_PLAYERS[4])} vs. {name(AWAY_PLAYERS[4])} "
    f"({name(HOME_PLAYERS[0])} gains possession)", HOME, [HOME_PLAYERS[4][0], AWAY_PLAYERS[4][0]])
for period in (1, 2, 3):
    play_period(period, 24, 720_000)
    add(period, 0, "End Period", f"End of the {period_display(period)}")
play_period(4, 24, 720_000)

# Force a tie at the end of regulation so overtime is exercised deterministically.
difference = score["home"] - score["away"]
trailing, trailing_roster = (AWAY, AWAY_PLAYERS) if difference > 0 else (HOME, HOME_PLAYERS)
remaining = abs(difference)
clock_millis = 9_000
while remaining > 0:
    shooter = trailing_roster[0]
    if remaining >= 3:
        add(4, clock_millis, "3-Point Jump Shot", f"{name(shooter)} makes 26-foot three point jumper",
            trailing, [shooter[0]], 3)
        remaining -= 3
    elif remaining == 2:
        add(4, clock_millis, "Driving Layup Shot", f"{name(shooter)} makes driving layup",
            trailing, [shooter[0]], 2)
        remaining -= 2
    else:
        add(4, clock_millis, "Free Throw - 1 of 1", f"{name(shooter)} makes free throw 1 of 1",
            trailing, [shooter[0]], 1)
        remaining -= 1
    clock_millis = max(0, clock_millis - 3_000)
assert score["home"] == score["away"], score
add(4, 0, "End Period", "End of the 4th Quarter")

play_period(5, 10, 300_000)
if score["home"] <= score["away"]:
    add(5, 4_000, "3-Point Jump Shot", f"{name(HOME_PLAYERS[0])} makes 27-foot three point jumper",
        HOME, [HOME_PLAYERS[0][0]], 3 + 0 * (score["away"] - score["home"]))
    while score["home"] <= score["away"]:
        add(5, 1_000, "Free Throw - 1 of 1", f"{name(HOME_PLAYERS[1])} makes free throw 1 of 1",
            HOME, [HOME_PLAYERS[1][0]], 1)
add(5, 0, "End Period", "End of the OT")
add(5, 0, "End Game", "End of Game")

# Correction: a second-quarter home three was later credited to a different home player.
correction_target = next(p for p in plays if p["period"] == 2 and p["team"] is HOME
                         and p["type"] == "3-Point Jump Shot")
original_player = next(p for p in HOME_PLAYERS if p[0] == correction_target["participants"][0])
corrected_player = next(p for p in HOME_PLAYERS if p[0] != original_player[0])
replacement = dict(correction_target)
replacement["participants"] = [corrected_player[0]]
replacement["text"] = f"{name(corrected_player)} makes 25-foot three point jumper"


def totals(sequence):
    points = {}
    for play in sequence:
        if play["scoring_play"]:
            points[play["participants"][0]] = points.get(play["participants"][0], 0) + play["score_value"]
    return dict(sorted(points.items()))


corrected_plays = [replacement if p["order"] == replacement["order"] else p for p in plays]

last = plays[-1]
game = {
    "id": GAME_ID, "date": START.strftime("%Y-%m-%d"), "season": 2025,
    "status": "Final", "status_state": "post", "period": 5, "time": "Final",
    "postseason": False, "postponed": False,
    "home_team_score": last["home_score"], "visitor_team_score": last["away_score"],
    "datetime": START.strftime("%Y-%m-%dT%H:%M:%S.000Z"),
    "home_team": HOME, "visitor_team": AWAY,
}
players = [
    {"id": player[0], "first_name": player[1], "last_name": player[2], "position": "G",
     "team": team}
    for team, roster in ((HOME, HOME_PLAYERS), (AWAY, AWAY_PLAYERS)) for player in roster
]
document = {
    "fixtureSchemaVersion": 1,
    "provider": "balldontlie",
    "providerSchema": "BALLDONTLIE v1 games/plays/players response shapes as documented on 2026-09-28",
    "provenance": "Entirely synthetic and authored for CourtPulse by generate_synthetic_overtime_game.py "
                  f"(seed {SEED}). Fictional teams, players, plays, and times; safe to redistribute.",
    "game": game,
    "players": players,
    "plays": plays,
    "correction": {"order": replacement["order"], "play": replacement},
    "expected": {
        "finalScore": {"home": last["home_score"], "away": last["away_score"]},
        "periods": 5,
        "plays": len(plays),
        "playerPoints": {str(k): v for k, v in totals(plays).items()},
        "correctedPlayerPoints": {str(k): v for k, v in totals(corrected_plays).items()},
        "correctedFrom": original_player[0],
        "correctedTo": corrected_player[0],
    },
}
print(json.dumps(document, indent=1))
