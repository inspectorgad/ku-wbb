#!/usr/bin/env python3
"""Regenerates app/src/main/assets/seed.json from scraped/ KU WBB data.

Inputs (all optional, produced by scrape-ku-wbb.mjs):
  scraped/ncaa-game-*.json  one per finished game: {gameId, date, info, box}
  scraped/roster.json       current roster from kuathletics.com
  scraped/upcoming.json     upcoming games from kuathletics.com

The seed is regenerated in full on every run — all data is scraper-owned, and
the app's Seeder merge is what protects user edits on-device.
"""
import glob
import json
import os
import re
from datetime import datetime, timezone

TEAM_SEO = "kansas"
SEED_PATH = "app/src/main/assets/seed.json"


def load_json(path, default):
    try:
        with open(path) as f:
            return json.load(f)
    except (OSError, json.JSONDecodeError):
        return default


def to_int(value):
    try:
        return int(str(value).strip() or 0)
    except ValueError:
        return 0


def to_minutes(value):
    """NCAA reports decimal minutes ("21.1"); round to whole minutes."""
    try:
        return round(float(str(value).strip() or 0))
    except ValueError:
        return 0


def season_label(start_year):
    """2025 -> "2025-26"."""
    return f"{start_year}-{str(start_year + 1)[-2:]}"


players = {}  # name.lower() -> {name, jerseyNumber, position}
games = {}  # (date, opponent.lower()) -> game dict


# Sources capitalize names inconsistently (e.g. "McCarthy" vs "Mccarthy"),
# so players are keyed case-insensitively; the roster's spelling wins.
def add_player(name, jersey, position, prefer=False):
    if not name:
        return
    existing = players.get(name.lower())
    if existing is None:
        players[name.lower()] = {"name": name, "jerseyNumber": jersey, "position": position}
    elif prefer:
        existing["name"] = name
        if jersey:
            existing["jerseyNumber"] = jersey
        if position:
            existing["position"] = position


def canonical_name(name):
    return players[name.lower()]["name"]


# --- Finished games from NCAA box scores ------------------------------------
for path in sorted(glob.glob("scraped/ncaa-game-*.json")):
    data = load_json(path, None)
    if not data:
        continue
    contests = (data.get("info") or {}).get("contests") or []
    if not contests:
        continue
    contest = contests[0]
    teams = contest.get("teams") or []
    ku = next((t for t in teams if t.get("seoname") == TEAM_SEO), None)
    opp = next((t for t in teams if t.get("seoname") != TEAM_SEO), None)
    if ku is None or opp is None:
        continue
    if contest.get("gameState") != "F":
        continue

    ku_home = bool(ku.get("isHome"))
    period_scores = []
    for ls in contest.get("linescores") or []:
        home, visit = to_int(ls.get("home")), to_int(ls.get("visit"))
        ours, theirs = (home, visit) if ku_home else (visit, home)
        period_scores.append(f"{ours}-{theirs}")

    # seasonYear is the season's start year (2025 for 2025-26). Games after
    # New Year fall in start_year+1, so the date alone can't be used.
    start_year = to_int(contest.get("seasonYear")) or int(data["date"][:4])
    game = {
        "date": data["date"],
        "opponent": opp.get("nameShort") or opp.get("nameFull") or "Unknown",
        "season": season_label(start_year),
        "teamScore": to_int(ku.get("score")),
        "opponentScore": to_int(opp.get("score")),
        "periodScores": ", ".join(period_scores),
        "lines": [],
    }

    ku_team_id = to_int(ku.get("teamId"))
    for tb in (data.get("box") or {}).get("teamBoxscore") or []:
        # teamBoxscore teamIds are numbers while teams[] carries strings.
        if to_int(tb.get("teamId")) != ku_team_id:
            continue
        for p in tb.get("playerStats") or []:
            name = f"{p.get('firstName', '').strip()} {p.get('lastName', '').strip()}".strip()
            add_player(name, str(p.get("number") or ""), p.get("position") or "")
            game["lines"].append({
                "player": name,
                "min": to_minutes(p.get("minutesPlayed")),
                "fgm": to_int(p.get("fieldGoalsMade")),
                "fga": to_int(p.get("fieldGoalsAttempted")),
                "tpm": to_int(p.get("threePointsMade")),
                "tpa": to_int(p.get("threePointsAttempted")),
                "ftm": to_int(p.get("freeThrowsMade")),
                "fta": to_int(p.get("freeThrowsAttempted")),
                "oreb": to_int(p.get("offensiveRebounds")),
                "reb": to_int(p.get("totalRebounds")),
                "ast": to_int(p.get("assists")),
                "to": to_int(p.get("turnovers")),
                "stl": to_int(p.get("steals")),
                "blk": to_int(p.get("blockedShots")),
                "pf": to_int(p.get("personalFouls")),
                "pts": to_int(p.get("points")),
                "gs": 1 if p.get("starter") else 0,
            })

    games[(game["date"], game["opponent"].lower())] = game

# --- Current roster (preferred source for number/position) ------------------
roster_names = set()
for entry in load_json("scraped/roster.json", []):
    name = entry.get("name", "").strip()
    roster_names.add(name)
    add_player(
        name,
        str(entry.get("jerseyNumber") or ""),
        (entry.get("position") or "").strip(),
        prefer=True,
    )

# active = on the current scraped roster. A failed/empty roster scrape must
# not mass-retire the team, so with an implausibly small roster the previous
# seed's flags are carried forward instead.
previous_seed = load_json(SEED_PATH, {})
previous_active = {
    (p.get("name") or "").lower(): p.get("active", True)
    for p in previous_seed.get("players", [])
}
roster_keys = {n.lower() for n in roster_names}
roster_valid = len(roster_keys) >= 8
for key, player in players.items():
    if roster_valid:
        player["active"] = key in roster_keys
    else:
        player["active"] = previous_active.get(key, True)

# Stat lines were recorded with whatever casing the box score used; align
# them with the canonical player names so the app can match them up.
for game in games.values():
    for line in game.get("lines", []):
        line["player"] = canonical_name(line["player"])

# --- Upcoming games (no results yet) -----------------------------------------
played_dates = {key[0] for key in games}
today = datetime.now(timezone.utc).strftime("%Y-%m-%d")
for entry in load_json("scraped/upcoming.json", []):
    date = entry.get("date", "")
    opponent = (entry.get("opponent") or "").strip()
    if not date or not opponent or date < today:
        continue
    key = (date, opponent.lower())
    if key in games:
        continue
    # A game in Jan-Apr belongs to the season that started the year before.
    year, month = int(date[:4]), int(date[5:7])
    start_year = year if month >= 8 else year - 1
    games[key] = {
        "date": date,
        "opponent": opponent,
        "season": season_label(start_year),
    }

# --- Big 12 standings, computed from the scoreboard sweep -------------------
# Conference records are derived from the Big 12 games the sweep collects
# (every scoreboard team carries a conference tag). This also means any season
# can be rebuilt retroactively, which a live standings endpoint could not do.
def norm_team(name):
    """Canonical key for cross-source name matching ('Iowa State'/'Iowa St.')."""
    n = re.sub(r"\s*\(\d+\)\s*$", "", name or "").lower()  # strip poll votes
    n = n.replace(".", "")
    n = re.sub(r"\bstate\b", "st", n)
    return re.sub(r"\s+", " ", n).strip()


index = load_json("scraped/ku-index.json", {})
big12_games = list(index.get("big12Games", {}).values())

# Conference records are regular-season only. The conference tournament is
# identifiable by scoreboard bracket fields; its first game dates the end of
# each season's regular season, which also fences off later postseason
# rematches between conference members (WBIT/NIT carry no bracket fields).
tournament_start = {}  # season label -> date of first conference-tournament game
for game in big12_games:
    if game.get("bracket") and game.get("conferenceGame"):
        start = to_int(game.get("season")) or to_int(game.get("date", "")[:4])
        season = season_label(start)
        date = game.get("date", "")
        if season not in tournament_start or date < tournament_start[season]:
            tournament_start[season] = date

records = {}  # (season label, key) -> record dict
for game in big12_games:
    # The sweep stores the season start year; standings use the app's
    # "2025-26" label so they line up with games.
    start = to_int(game.get("season")) or to_int(game.get("date", "")[:4])
    season = season_label(start)
    cutoff = tournament_start.get(season)
    conf_game = (
        bool(game.get("conferenceGame"))
        and not game.get("bracket")
        and (cutoff is None or game.get("date", "") < cutoff)
    )
    for side in ("home", "away"):
        s = game.get(side) or {}
        if not s.get("inConference") or not s.get("name"):
            continue  # non-conference opponents get no standings row
        rec = records.setdefault(
            (season, norm_team(s["name"])),
            {
                "season": season,
                "team": s["name"],
                "seo": s.get("seo", ""),
                "confW": 0, "confL": 0, "overallW": 0, "overallL": 0,
                "_rankDate": "", "nationalRank": None,
            },
        )
        won = bool(s.get("winner"))
        rec["overallW" if won else "overallL"] += 1
        if conf_game:
            rec["confW" if won else "confL"] += 1
        # Keep the most recent rank the scoreboard reported that season.
        if s.get("rank") and game.get("date", "") >= rec["_rankDate"]:
            rec["_rankDate"] = game["date"]
            rec["nationalRank"] = s["rank"]

# --- Rankings snapshots (AP poll + NCAA NET) ---------------------------------
# Both endpoints serve only the current snapshot, so each is keyed by the
# season in its "Through Games APR. 5, 2026" label (Aug-Dec dates belong to the
# season starting that year; Jan-Jul to the one that started the year before).
MONTHS = "JAN FEB MAR APR MAY JUN JUL AUG SEP OCT NOV DEC".split()


def snapshot_season(payload):
    label = (payload.get("updated", "") or "").upper()
    m = re.search(r"\b(" + "|".join(MONTHS) + r")[A-Z]*\.?\s+\d{1,2},?\s+(20\d{2})", label)
    if not m:
        return None
    month = MONTHS.index(m.group(1)) + 1
    year = int(m.group(2))
    return season_label(year if month >= 8 else year - 1)


def row_value(row, *candidates):
    """First matching column: sources vary in header capitalization."""
    lowered = {k.strip().lower(): v for k, v in row.items()}
    for c in candidates:
        if c in lowered:
            return lowered[c]
    return None


polls = []
ap = load_json("scraped/rankings-ap.json", {})
net = load_json("scraped/rankings-net.json", {})

net_season = snapshot_season(net)
net_by_team = {}
for row in net.get("data", []):
    if (row_value(row, "conf", "conference") or "") == "Big 12":
        net_by_team[norm_team(row_value(row, "school", "team") or "")] = row

# NET carries each team's official overall record — fold in the NET rank and
# cross-check our computed record against it (a warning, never a failure: the
# snapshot and our sweep can legitimately sit a game apart mid-season).
for (season, key), rec in records.items():
    row = net_by_team.get(key)
    if not row or season != net_season:
        continue
    rank = str(row_value(row, "rank") or "")
    rec["netRank"] = int(rank) if rank.isdigit() else None
    official = (row_value(row, "record") or "").strip()
    ours = f"{rec['overallW']}-{rec['overallL']}"
    if official and official != ours:
        print(f"  cross-check: {rec['team']} computed {ours} vs NET {official}")

ap_season = snapshot_season(ap)
if ap.get("data") and ap_season:
    b12_keys = {k for (s, k) in records if s == ap_season}
    rows = []
    for row in ap["data"]:
        label = str(row_value(row, "rank") or "").strip()  # can be a tie, e.g. "T-22."
        digits = re.search(r"\d+", label)
        raw_team = (row_value(row, "school (1st votes)", "school", "team") or "").strip()
        team = re.sub(r"\s*\(\d+\)\s*$", "", raw_team).strip()
        votes = re.search(r"\((\d+)\)\s*$", raw_team)
        rows.append({
            "rank": int(digits.group()) if digits else 0,
            "rankLabel": label.rstrip("."),
            "team": team,
            "record": (row_value(row, "record") or "").strip(),
            "points": (row_value(row, "points", "total points") or "").strip(),
            "previous": (row_value(row, "previous", "prev") or "").strip(),
            "firstPlaceVotes": int(votes.group(1)) if votes else 0,
            "big12": norm_team(team) in b12_keys,
        })
    polls.append({
        "season": ap_season,
        "name": "AP Top 25",
        "updated": (ap.get("updated") or "").strip(),
        "rows": rows,
    })

    # The poll is the authoritative ranking. The scoreboard's per-game rank is
    # only "the rank this team carried in that game", so a team that fell out
    # of the top 25 would otherwise keep a stale number forever. Where we hold
    # a poll for the season, it decides: absent from the poll means unranked.
    poll_rank = {norm_team(r["team"]): r["rank"] for r in rows}
    restated = 0
    for (season, key), rec in records.items():
        if season != ap_season:
            continue
        fresh = poll_rank.get(key)
        if fresh != rec["nationalRank"]:
            restated += 1
        rec["nationalRank"] = fresh
    if restated:
        print(f"  national ranks restated from the poll for {restated} teams")
    print(
        f"  AP poll {ap_season}: {len(rows)} teams, "
        f"{sum(1 for r in rows if r['big12'])} from the Big 12"
    )

# Sorted by conference win %, then conference wins, then overall win % — NOT
# official Big 12 tiebreakers (those use head-to-head); the UI says as much.
def standing_sort(rec):
    conf_games = rec["confW"] + rec["confL"]
    overall = rec["overallW"] + rec["overallL"]
    return (
        -(rec["confW"] / conf_games if conf_games else 0),
        -rec["confW"],
        -(rec["overallW"] / overall if overall else 0),
        rec["team"],
    )


standings = []
for rec in sorted(records.values(), key=standing_sort):
    standings.append({k: v for k, v in rec.items() if not k.startswith("_")})
if standings:
    seasons = sorted({r["season"] for r in standings})
    print(f"standings computed for seasons {', '.join(seasons)}: {len(standings)} team rows")

seed = {
    "formatVersion": 1,
    "generatedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    "team": "Kansas Jayhawks Women's Basketball",
    "players": sorted(players.values(), key=lambda p: p["name"]),
    "games": [games[k] for k in sorted(games)],
}
# Additive only: formatVersion stays 1 so already-installed APKs (which reject
# anything newer) keep syncing, and older seeds without these keys stay valid.
if standings:
    seed["standings"] = standings
if polls:
    seed["polls"] = polls

os.makedirs(os.path.dirname(SEED_PATH), exist_ok=True)

# Skip the write when nothing but the timestamp would change, so the nightly
# job doesn't commit (and rebuild the APK) on quiet days.
previous = load_json(SEED_PATH, {})
current_cmp = {k: v for k, v in seed.items() if k != "generatedAt"}
previous_cmp = {k: v for k, v in previous.items() if k != "generatedAt"}
if current_cmp == previous_cmp:
    print("seed.json unchanged (ignoring timestamp); not rewriting")
else:
    with open(SEED_PATH, "w") as f:
        json.dump(seed, f, indent=1)
        f.write("\n")
    print(
        f"seed.json written: {len(seed['players'])} players, "
        f"{len(seed['games'])} games "
        f"({sum(1 for g in seed['games'] if 'teamScore' in g)} with results)"
    )
