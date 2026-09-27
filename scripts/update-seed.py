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
# KU's home floor. A game played anywhere else is not a home game, whatever the
# feed's isHome flag says — it marks a nominal home side even at neutral sites.
HOME_VENUE = "Allen Fieldhouse"
NEUTRAL_SITES_PATH = "scripts/neutral-sites.json"


def fix_mojibake(text):
    """Repair UTF-8 read as Latin-1 ("AnaÃ«lle" -> "Anaëlle").

    The NCAA feed is inconsistent between games, so the same player can arrive
    corrupted in one box score and clean in another; without this they become
    two different players in every cross-game aggregation.
    """
    if not text or not any(c in text for c in "ÃÂÅ"):
        return text
    try:
        repaired = text.encode("latin-1").decode("utf-8")
    except (UnicodeEncodeError, UnicodeDecodeError):
        return text
    # Only accept a repair that removes the tell-tale sequences.
    return repaired if "Ã" not in repaired else text


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


def norm_team(name):
    """Canonical key for cross-source name matching ('Iowa State'/'Iowa St.').

    kuathletics writes a schedule opponent as "South Dakota State" while the
    NCAA box score for the same game says "South Dakota St." — without this the
    fixture and its result key differently and the season shows two games.
    """
    n = re.sub(r"\s*\(\d+\)\s*$", "", name or "").lower()  # strip poll votes
    n = n.replace(".", "")
    n = re.sub(r"\bstate\b", "st", n)
    n = re.sub(r"\buniversity\b", "", n)
    return re.sub(r"\s+", " ", n).strip()


def game_key(date, opponent):
    """Games are identified by date + normalized opponent, never raw casing."""
    return (date, norm_team(opponent))


players = {}  # name.lower() -> {name, jerseyNumber, position}
games = {}  # (date, opponent.lower()) -> game dict


# Sources capitalize names inconsistently (e.g. "McCarthy" vs "Mccarthy"),
# so players are keyed case-insensitively; the roster's spelling wins.
def add_player(name, jersey, position, prefer=False, bio=None):
    if not name:
        return
    existing = players.get(name.lower())
    if existing is None:
        players[name.lower()] = {"name": name, "jerseyNumber": jersey, "position": position}
        existing = players[name.lower()]
    elif prefer:
        existing["name"] = name
        if jersey:
            existing["jerseyNumber"] = jersey
        if position:
            existing["position"] = position
    # Height, class, hometown and previous school come only from kuathletics;
    # a former player keeps whatever was last known rather than losing it.
    for key, value in (bio or {}).items():
        if value:
            existing[key] = value


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

    # isHome marks the nominal home side, which at a neutral site is whoever the
    # bracket seeded higher — so it decides scoring order but never the venue.
    ku_nominal_home = bool(ku.get("isHome"))
    period_scores = []
    for ls in contest.get("linescores") or []:
        home, visit = to_int(ls.get("home")), to_int(ls.get("visit"))
        ours, theirs = (home, visit) if ku_nominal_home else (visit, home)
        period_scores.append(f"{ours}-{theirs}")

    # seasonYear is the season's start year (2025 for 2025-26). Games after
    # New Year fall in start_year+1, so the date alone can't be used.
    start_year = to_int(contest.get("seasonYear")) or int(data["date"][:4])
    location = contest.get("location") or {}
    game = {
        "date": data["date"],
        "opponent": opp.get("nameShort") or opp.get("nameFull") or "Unknown",
        "season": season_label(start_year),
        "venue": (location.get("venue") or "").strip(),
        "city": ", ".join(p for p in [
            (location.get("city") or "").strip(),
            (location.get("stateUsps") or "").strip(),
        ] if p),
        "nominalHome": ku_nominal_home,
        "teamScore": to_int(ku.get("score")),
        "opponentScore": to_int(opp.get("score")),
        "periodScores": ", ".join(period_scores),
        "lines": [],
    }
    # What the opponent brought into the game: their national rank if they had
    # one, their tournament seed, and their record to that point. All three are
    # context the box score carries and the seed has been dropping.
    opp_rank = to_int(opp.get("teamRank") or opp.get("gameRank"))
    if opp_rank:
        game["opponentRank"] = opp_rank
    opp_seed = to_int(opp.get("seed"))
    if opp_seed:
        game["opponentSeed"] = opp_seed
    # Written "(19-2)" by the feed; the parentheses are presentation.
    opp_record = (opp.get("record") or "").strip().strip("()")
    if opp_record and opp_record != "0-0":
        game["opponentRecord"] = opp_record

    # Overtime, from the linescore period labels ("OT", "2OT") or the final
    # message. Four quarters is regulation; anything beyond it is not.
    ot_periods = [
        str(ls.get("period") or "") for ls in (contest.get("linescores") or [])
        if "OT" in str(ls.get("period") or "").upper()
    ]
    if ot_periods or "OT" in str(contest.get("currentPeriod") or "").upper():
        game["overtime"] = len(ot_periods) or 1

    ku_team_id = to_int(ku.get("teamId"))
    for tb in (data.get("box") or {}).get("teamBoxscore") or []:
        # teamBoxscore teamIds are numbers while teams[] carries strings.
        is_ku = to_int(tb.get("teamId")) == ku_team_id
        # Official team totals for BOTH sides. These are not the sum of the
        # player lines: team rebounds (deadballs) and team turnovers belong to
        # no player, so summing box lines understates them — KU's 2025-26
        # rebounding is 35.0/game officially against 30.8 summed.
        stats = tb.get("teamStats") or {}
        if stats:
            game["teamStats" if is_ku else "opponentStats"] = {
                "fgm": to_int(stats.get("fieldGoalsMade")),
                "fga": to_int(stats.get("fieldGoalsAttempted")),
                "tpm": to_int(stats.get("threePointsMade")),
                "tpa": to_int(stats.get("threePointsAttempted")),
                "ftm": to_int(stats.get("freeThrowsMade")),
                "fta": to_int(stats.get("freeThrowsAttempted")),
                "oreb": to_int(stats.get("offensiveRebounds")),
                "reb": to_int(stats.get("totalRebounds")),
                "ast": to_int(stats.get("assists")),
                "to": to_int(stats.get("turnovers")),
                "stl": to_int(stats.get("steals")),
                "blk": to_int(stats.get("blockedShots")),
                "pf": to_int(stats.get("personalFouls")),
                "pts": to_int(stats.get("points")),
            }
        if not is_ku:
            # The other side's box score, already downloaded and until now
            # discarded. Stored flat rather than through the player table:
            # these are not KU players and must never join the roster.
            game["opponentLines"] = [{
                "player": f"{fix_mojibake((p.get('firstName') or '').strip())} "
                          f"{fix_mojibake((p.get('lastName') or '').strip())}".strip(),
                "number": str(p.get("number") or ""),
                "position": (p.get("position") or "").strip(),
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
            } for p in (tb.get("playerStats") or [])
                if (p.get("firstName") or p.get("lastName"))]
            continue
        for p in tb.get("playerStats") or []:
            first = fix_mojibake((p.get("firstName") or "").strip())
            last = fix_mojibake((p.get("lastName") or "").strip())
            name = f"{first} {last}".strip()
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

    games[game_key(game["date"], game["opponent"])] = game

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
        bio={
            "height": (entry.get("height") or "").strip(),
            "academicYear": (entry.get("academicYear") or "").strip(),
            "hometown": (entry.get("hometown") or "").strip(),
            "lastSchool": (entry.get("lastSchool") or "").strip(),
        },
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
    key = game_key(date, opponent)
    if key in games:
        continue
    # A game in Jan-Apr belongs to the season that started the year before.
    year, month = int(date[:4]), int(date[5:7])
    start_year = year if month >= 8 else year - 1
    fixture = {
        "date": date,
        "opponent": opponent,
        "season": season_label(start_year),
        # The schedule page names the venue, so a fixture already knows whether
        # it is home, away or on a neutral floor — no need to wait for the box
        # score. `scheduleSite` is trusted directly by the site derivation.
        "scheduleSite": (entry.get("site") or "").strip() or None,
        "nominalHome": bool(entry.get("home")),
    }
    for key_name in ("venue", "city", "tv", "event"):
        value = (entry.get(key_name) or "").strip()
        if value:
            fixture[key_name] = value
    # Tip-off, local to the venue, as "18:30". Absent until the conference sets
    # the TV windows, so it stays out of the seed rather than being guessed.
    tip = (entry.get("time") or "").strip()
    if tip:
        fixture["time"] = tip
    games[key] = fixture

# --- Home / away / neutral ---------------------------------------------------
# Derived from the venue, never from the feed's isHome: at a neutral site that
# flag marks the higher seed, so it called the Big 12 Tournament game against
# UCF a home game and five genuinely neutral games road games.
neutral_cfg = load_json(NEUTRAL_SITES_PATH, {})
neutral_games = set(neutral_cfg.get("games", {}))
neutral_venues = set(neutral_cfg.get("venues", {}))

# A non-Allen venue hosting KU against two or more different opponents in one
# season is a tournament floor — detected rather than configured, so holiday
# and conference tournaments need no upkeep.
venue_opponents = {}
for game in games.values():
    venue = game.get("venue")
    if venue and venue != HOME_VENUE:
        venue_opponents.setdefault((game["season"], venue), set()).add(
            norm_team(game["opponent"])
        )

site_counts = {"home": 0, "away": 0, "neutral": 0}
for key, game in games.items():
    venue = game.get("venue")
    marker = f"{game['date']}|{norm_team(game['opponent'])}"
    played = game.get("teamScore") is not None
    if marker in neutral_games:
        site = "neutral"
    elif not played and game.get("scheduleSite"):
        # An unplayed fixture: the schedule page states the side AND the venue,
        # which together settle it — "vs" at anyone else's building is neutral.
        site = game["scheduleSite"]
    elif venue == HOME_VENUE:
        site = "home"
    elif venue and (
        venue in neutral_venues
        or len(venue_opponents.get((game["season"], venue), ())) > 1
    ):
        site = "neutral"
    elif venue:
        site = "away"
    else:
        # Nothing to go on but the schedule page's vs/at, which alone cannot
        # express neutral. Corrected once the game is played.
        site = "home" if game.get("nominalHome") else "away"
    game["site"] = site
    # Kept so an older installed APK, which knows only this field, still
    # renders something sane for a neutral game rather than nothing.
    game["home"] = site == "home"
    game.pop("nominalHome", None)
    game.pop("scheduleSite", None)
    site_counts[site] += 1

print(
    "sites: "
    + ", ".join(f"{n} {s}" for s, n in site_counts.items())
    + f" (home venue {HOME_VENUE})"
)
for game in sorted(games.values(), key=lambda g: g["date"]):
    if game["site"] == "neutral":
        print(f"  neutral: {game['date']} vs {game['opponent']} at {game.get('venue') or '?'}")

# --- Big 12 standings, computed from the scoreboard sweep -------------------
# Conference records are derived from the Big 12 games the sweep collects
# (every scoreboard team carries a conference tag). This also means any season
# can be rebuilt retroactively, which a live standings endpoint could not do.
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

# --- Conference games ---------------------------------------------------------
# A KU game counts toward the Big 12 record when the opponent is a member that
# season, the game is regular season, and it falls before the conference
# tournament. Membership is read from the sweep's own conference tags, never a
# hardcoded list. Getting any of the three wrong inflates the record: a naive
# membership lookup alone gives 21 games and 9-12 where the official figure is
# 18 and 8-10.
big12_members = {}
for (season, key) in records:
    big12_members.setdefault(season, set()).add(key)

# KU's own games as the sweep saw them, so the bracket flag carries over.
ku_bracket = set()
for g in big12_games:
    sides = [g.get("home") or {}, g.get("away") or {}]
    if any((s.get("seo") or "") == TEAM_SEO for s in sides) and g.get("bracket"):
        other = next((s for s in sides if (s.get("seo") or "") != TEAM_SEO), {})
        ku_bracket.add((g.get("date", ""), norm_team(other.get("name", ""))))

conference_counts = {}
for game in games.values():
    season = game["season"]
    key = norm_team(game["opponent"])
    cutoff = tournament_start.get(season)
    game["conference"] = bool(
        key in big12_members.get(season, ())
        and (game["date"], key) not in ku_bracket
        and (cutoff is None or game["date"] < cutoff)
    )
    if game["conference"]:
        conference_counts[season] = conference_counts.get(season, 0) + 1
for season, count in sorted(conference_counts.items()):
    print(f"conference games {season}: {count}")

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

# The NET table lists every Division I team, so an opponent absent from it is
# not Division I. That matters because the NCAA's own record and site splits
# exclude such games: KU's 22-14 is 21-14 to the NET because of Haskell.
d1_teams = {
    norm_team(row_value(row, "school", "team") or "")
    for row in net.get("data", [])
}
if d1_teams and net_season:
    non_d1 = set()
    for game in games.values():
        if game["season"] != net_season or game.get("teamScore") is None:
            continue
        if norm_team(game["opponent"]) not in d1_teams:
            game["nonD1"] = True
            non_d1.add(game["opponent"])
    if non_d1:
        print(f"non-D1 opponents {net_season}: {', '.join(sorted(non_d1))}")

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

# Flat tables Claude can load and compute on, for "Ask about the team"
# (scripts/ask_pack.py). Written from the seed that was just validated, and
# only when something other than the timestamp changed.
import sys  # noqa: E402

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ask_pack import write_pack  # noqa: E402

if write_pack(seed, "docs/ask-data.json"):
    print("ask data written: docs/ask-data.json")
else:
    print("ask data unchanged (ignoring timestamp); not rewriting")
