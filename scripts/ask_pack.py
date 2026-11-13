"""The season as flat tables, for "Ask about the team".

The seed is shaped for the app: nested, merged, full of display details. A
question put to Claude is better answered from plain tables it can load into
pandas and compute on, so the numbers come from code rather than from reading
a long document. This writes docs/ask-data.json: one list of flat records per
table, a data dictionary saying what every column means, and the few facts a
reader needs to interpret them (what "site" means, why the team totals are not
the sum of the player lines, how true shooting is worked out).

Everything here is derived from the seed that was just validated against the
NCAA box scores; nothing is recomputed or estimated.
"""

import json
import os
import re

from seed_text import norm_team, season_label, snapshot_season

# The box score, in the order the NCAA publishes it. Player lines carry `min`
# and `gs` on top of these; the official team totals carry only these.
STAT_COLS = ["fgm", "fga", "tpm", "tpa", "ftm", "fta", "oreb", "reb",
             "ast", "to", "stl", "blk", "pf", "pts"]

DEFINITIONS = {
    "min": "minutes played",
    "fgm / fga": "field goals made and attempted (twos and threes together)",
    "tpm / tpa": "three-pointers made and attempted",
    "ftm / fta": "free throws made and attempted",
    "oreb": "offensive rebounds", "reb": "total rebounds, offensive and defensive",
    "ast": "assists", "to": "turnovers", "stl": "steals", "blk": "blocked shots",
    "pf": "personal fouls", "pts": "points",
    "started": "in the starting five for that game",
    "height": "an opposing player's listed height, from her own school's roster page — a box "
              "score carries no height for anyone but Kansas. Null for about five lines in six, "
              "and the reason is the calendar, not a failed scrape: a roster page lists whoever "
              "is on the team now, so matched against a season already played it only reaches "
              "the players who stayed. About half the misses are schools with no roster page "
              "mapped at all; the rest are players who have since graduated or transferred",
    "efg_pct": "effective field goal percentage, (fgm + 0.5 * tpm) / fga — a three counts "
               "for the extra half a basket it is worth",
    "ts_pct": "true shooting percentage, pts / (2 * (fga + 0.44 * fta)) — points per scoring "
              "attempt, free throws included; the 0.44 estimates how many attempts a trip to "
              "the line represents",
    "three_point_rate": "tpa / fga, the share of shots taken from behind the arc",
    "free_throw_rate": "fta / fga, how often attacking turns into trips to the line",
    "site": "H home (Allen Fieldhouse), A away, N neutral — derived from the venue, not from "
            "the feed's own home flag, which at a neutral site only marks the higher seed",
    "conference": "true for a regular-season game against a Big 12 member; the conference "
                  "tournament is not part of the Big 12 record",
    "non_d1": "the opponent is not Division I, so the NCAA's own record and site splits leave "
              "this game out",
    "overtime": "number of overtime periods, null in regulation",
    "opp_rank": "the opponent's AP rank going into the game (null = unranked)",
    "opp_seed": "the opponent's tournament seed, where the game was a tournament game",
    "opp_record": "the opponent's won-lost record going into the game",
    "period_scores": "points by quarter from KU's side, e.g. '25-14, 17-23, 29-16, 15-22'; "
                     "the periods table has the same thing one row per quarter",
    "exhibition": "an exhibition game: it counts for nothing and is not in any record",
    "team_key / opponent_key / home_key / away_key": "one normalized spelling per school, with "
        "'State' folded to 'st', punctuation dropped, a leading poll rank ('#25 Nebraska') and a "
        "trailing '(exh.)' removed. Every table that names a school carries one. Match teams on "
        "these keys and never on the display names: a school is printed differently between "
        "sources and between seasons ('Iowa State' in upcoming, 'Iowa St.' in last season's "
        "games), so joining on the printed name silently matches nothing",
    "as_of": "the date the source table was published. team_records carries the NET as it stood "
             "then, which before about the first Monday of December is last season's final table, "
             "not this season's — its season column says which",
    "home / away (big12_results)": "the scoreboard's nominal sides. At a neutral venue — the Big "
        "12 tournament, holiday events — neither team is really at home, so read them as sides "
        "rather than as home-court advantage",
    "conference_game": "true for a regular-season game between two Big 12 members, before that "
                       "season's conference tournament. Computed the same way the standings are, "
                       "not copied from the scoreboard's own flag, which is also true for the "
                       "conference tournament's own games",
    "bracket": "the game was part of a tournament bracket (the Big 12 tournament, the NCAA "
               "tournament), so it is outside the regular-season conference record",
}


# The instructions Claude is given, shared by the dashboard (docs/ask.js) and
# the app (AskEngine.kt) so both ask the same way. The tables are fetched by
# Claude's own code through the get_table tool; this only says how.
SYSTEM_RULES = """You are the analyst behind the Kansas Jayhawks women's basketball app and dashboard. You answer questions from coaches and fans about the team and the teams it plays, using the season data you are given - and the web, when the reader turns web search on for a question.

The complete data is available to your Python code through the get_table tool: tables games, periods (one row per quarter, with each side's points), ku_lines (KU player box score lines per game), team_totals (the official team totals for both sides), opponent_lines, opponent_roster (opposing squads as their schools list them today — the right source for a game not yet played, and the wrong one for a season already played, where it only reaches the players who have not left since), upcoming, standings, poll, roster, definitions, team_records (every Division I team's NET row), big12_results (every final involving a Big 12 team) and d1_results (every final on the NCAA's Division I scoreboard this season). Call it from inside code execution, for example: import json, pandas as pd; lines = pd.DataFrame(json.loads(await get_table({'table': 'ku_lines'}))). Join tables on (season, date, opponent). A summary of the smaller tables is below for orientation.

How to answer:
- Compute every number with code from the tables. Do not estimate, recall, or do arithmetic in your head, even for a simple total.
- For anything about the team as a whole — rebounds, turnovers, shooting, points — use team_totals, not the sum of ku_lines. Team rebounds (deadballs) and team turnovers belong to no individual, so the player lines add up short: KU's rebounding is about four a game higher in the official totals than in the sum of the lines.
- Lead with the answer in a sentence or two. Add a small markdown table when it helps. End with one short line saying what the figures cover (which season, which games, any filter).
- Use basketball conventions: percentages as .447, per-game rates to one decimal, records as 8-10.
- Records and site splits: the NCAA leaves out games against non-Division I opponents (non_d1), so say which convention you used when it changes the answer. The Big 12 record counts only rows where conference is true.
- Name small samples plainly (for example "only 3 games").
- If the data cannot answer the question — injuries, practice, line-ups, recruiting, play-by-play, anything not in the box scores — say so in a sentence instead of guessing; with web search on, look for it on the web instead.

Other teams: team_records is every Division I team's NET row — rank, previous rank, conference, and the overall, home, road, neutral, non-Division I and Quadrant 1-4 records. It carries the season it belongs to and the date it was published, and until the NCAA publishes a season's first NET, around the first Monday of December, it is last season's final table: say which season a NET figure is from rather than presenting an old one as current. big12_results is every final involving a Big 12 team, with each side's points and its AP rank going into that game, so records against ranked teams and points for and against can be computed from it; it reaches back a season, but holds no game between two teams from outside the conference. d1_results is the wider sweep — every final the NCAA's Division I scoreboard listed this season, in the same columns — so it is the table to join on for common opponents, and the only one that covers two teams who have both yet to play a Big 12 side. Two warnings about it. It is this season only, and that scoreboard lists a Division I team's games against non-Division I opponents too: about a third of its rows have a side that has no team_records row, and unlike games it carries no non_d1 flag, so drop a side that is missing from team_records before computing a record from it. And neither results table is promised to be complete: a comparison that rests on finding no game, rather than on the games it found, should say so. opponent_roster is the squads of the schools on our schedule, as those schools list them today. Match teams across all of these on the *_key columns, never on the printed names.

Web search: the reader can turn on web search for a question, and each question says whether it is on. When it is off, answer from the data only, and say plainly when the data cannot answer. When it is on, you may use the web_search tool for what the tables cannot tell you — previews, recaps, injuries and line-up news, coaches' comments, transfers, rankings commentary — preferring official athletics sites, the Big 12, the NCAA, the AP, the WBCA and established news outlets. Even then, take every statistic from the tables when they have it, and keep web material for context. Make clear which statements come from the web, and give each article's date when it matters, since news goes out of date. Search only as much as the question needs.
"""


# Where the scrape leaves the files these tables are built from. They are not
# carried in the seed: the app downloads the seed on every sync and would be
# paying for ~200KB of other teams' data it never reads, and `scraped/` is
# committed, so CI and the nightly run both have it.
SCRAPED = "scraped"


def _scraped(name, root=SCRAPED):
    """One scraped file, or None when it is not there.

    The tests build a pack from a fixture seed with no scraped/ beside it, so
    every table built from one of these must come out as [] - never missing,
    and never None.
    """
    try:
        with open(os.path.join(root, name)) as f:
            return json.load(f)
    except (OSError, ValueError):
        return None


# The AP and the NET spell a few schools differently; without these the two
# tables would not join. Kept beside team_key so the list is where it is used.
_ALIASES = {"southern california": "southern cal", "usc": "southern cal"}


def team_key(name):
    """One normalized spelling per school, for matching teams across tables.

    Built on seed_text.norm_team, which the seed's own joins already use, so
    the opponent_roster keys stored in the seed stay valid. It adds the three
    things norm_team deliberately leaves alone:

    - a leading poll rank, because the upcoming table prints "#25 Nebraska";
    - a trailing "(exh.)", so an exhibition joins the other tables;
    - the handful of schools the AP and the NET spell differently.

    norm_team has to keep "(exh.)" because update-seed.py decides from it
    whether a game is a conference game. Stripping it here cannot change that:
    the conference column still comes from the seed's own flag.
    """
    n = re.sub(r"^#\d+\s+", "", (name or "").strip())
    n = re.sub(r"\s*\((?:[Ee]xh\.?|[Ee]xhibition)\)\s*$", "", n)
    n = norm_team(n)
    return _ALIASES.get(n, n)



def _row_value(row, *candidates):
    """First matching column; the NCAA's tables vary in header capitalization."""
    lowered = {k.strip().lower(): v for k, v in (row or {}).items()}
    for c in candidates:
        if c in lowered:
            return lowered[c]
    return None


def _to_int(value):
    try:
        return round(float(str(value).strip() or 0))
    except (TypeError, ValueError):
        return 0


def net_table(payload):
    """Every Division I team's NET row, with the season it belongs to.

    The NCAA publishes no formula behind the NET, so this is its table as
    published, not a rating worked out here. The season matters: until the
    first NET of a season appears, around the first Monday of December, this
    file is last season's final one.
    """
    payload = payload or {}
    season, as_of = snapshot_season(payload), (payload.get("updated") or "").strip() or None
    rows = []
    for row in payload.get("data", []):
        rank = str(_row_value(row, "rank") or "").strip()
        team = _row_value(row, "school", "team") or ""
        if not rank.isdigit() or not team:
            continue
        rows.append({
            "rank": int(rank), "team": team, "team_key": team_key(team),
            "previous": _to_int(_row_value(row, "prev", "previous")) or None,
            "conference": _row_value(row, "conf", "conference") or None,
            "record": _row_value(row, "record") or None,
            "home": _row_value(row, "home") or None,
            "road": _row_value(row, "road") or None,
            "neutral": _row_value(row, "neutral") or None,
            "non_d1": _row_value(row, "non-div i", "non-div 1", "non-divi") or None,
            "quad1": _row_value(row, "quad 1") or None,
            "quad2": _row_value(row, "quad 2") or None,
            "quad3": _row_value(row, "quad 3") or None,
            "quad4": _row_value(row, "quad 4") or None,
            "season": season, "as_of": as_of,
        })
    return rows


def _result_rows(games, tournament_start):
    """Finals as flat rows, with conference_game on the standings' own rule."""
    rows = []
    for g in games:
        home, away = g.get("home") or {}, g.get("away") or {}
        if home.get("score") is None or away.get("score") is None:
            continue  # not played yet; the schedule is the upcoming table's job
        start = _to_int(g.get("season")) or _to_int((g.get("date") or "")[:4])
        season = season_label(start)
        cutoff = tournament_start.get(season)
        rows.append({
            "date": g.get("date") or "", "season": season,
            "home": home.get("name") or "", "home_key": team_key(home.get("name")),
            "home_points": home.get("score"), "home_rank": home.get("rank"),
            "away": away.get("name") or "", "away_key": team_key(away.get("name")),
            "away_points": away.get("score"), "away_rank": away.get("rank"),
            # The standings rule, not the scoreboard's own conferenceGame: that
            # flag is also true for the Big 12 tournament's own games, so
            # copying it would disagree with the standings table.
            "conference_game": (
                bool(g.get("conferenceGame"))
                and not g.get("bracket")
                and (cutoff is None or (g.get("date") or "") < cutoff)
            ),
            "bracket": bool(g.get("bracket")),
        })
    rows.sort(key=lambda r: (r["date"], r["home"], r["away"]))
    return rows


def _tournament_start(games):
    """Season label -> the date of its first conference-tournament game."""
    out = {}
    for g in games:
        if not (g.get("bracket") and g.get("conferenceGame")):
            continue
        start = _to_int(g.get("season")) or _to_int((g.get("date") or "")[:4])
        season, date = season_label(start), g.get("date") or ""
        if season not in out or date < out[season]:
            out[season] = date
    return out


def results_tables(index, d1):
    """(big12_results, d1_results) from the sweep's own files.

    big12_results is every final involving a Big 12 team; d1_results is every
    Division I final the sweep saw. The second is a superset, so the
    conference-tournament cutoff is read from both together: a Big 12
    tournament game is in the index whichever file it came from.
    """
    big12 = list((index or {}).get("big12Games", {}).values())
    every = list((d1 or {}).get("games", {}).values())
    cutoff = _tournament_start(big12 + every)
    return _result_rows(big12, cutoff), _result_rows(every, cutoff)


def _cell(v):
    if v is None:
        return ""
    if isinstance(v, bool):
        return "true" if v else "false"
    s = str(v)
    return '"' + s.replace('"', '""') + '"' if any(ch in s for ch in '",\n') else s


def _csv(rows, cols):
    return "\n".join([",".join(cols)] + [",".join(_cell(r.get(c)) for c in cols) for r in rows])


def _season_line(pack):
    """Which season is which, counted from the data rather than assumed."""
    seasons = sorted({g["season"] for g in pack["games"]} | {u["season"] for u in pack["upcoming"]})
    played = {s: sum(1 for g in pack["games"] if g["season"] == s) for s in seasons}
    scheduled = {s: sum(1 for u in pack["upcoming"] if u["season"] == s) for s in seasons}
    current = seasons[-1] if seasons else None
    parts = [f"{s}: {played[s]} games played" +
             (f", {scheduled[s]} still scheduled" if scheduled[s] else "") for s in seasons]
    line = "Seasons in the data — " + "; ".join(parts) + "."
    if current:
        line += (f" {current} is the current season, and \"this season\" means {current} unless the "
                 f"reader says otherwise")
        if not played.get(current):
            line += (f"; it has not been played yet, so say that rather than answering from an "
                     f"earlier season")
        line += "."
    return line


def system_prompt(p):
    """The rules plus a compact summary of the smaller tables, in fixed column
    order so the text — and the prompt cache — changes only with the data."""
    current = p["games"][-1]["season"] if p["games"] else None
    parts = [
        f"Data generated {p['generated_at']}.",
        _season_line(p),
        "Definitions:\n" + "\n".join(f"- {k}: {v}" for k, v in p["definitions"].items()),
        "Played games:\n" + _csv(p["games"], ["season", "date", "opponent", "site", "conference",
                                              "non_d1", "result", "ku_points", "opp_points",
                                              "overtime", "opp_rank", "opp_seed", "opp_record",
                                              "period_scores"]),
        "Scheduled games:\n" + _csv(p["upcoming"], ["season", "date", "opponent", "site",
                                                    "conference", "exhibition", "venue", "city",
                                                    "tip_time_local", "tv", "event"]),
        (f"Big 12 standings {current}:\n" + _csv([s for s in p["standings"] if s["season"] == current],
                                                 ["team", "confW", "confL", "overallW", "overallL",
                                                  "nationalRank", "netRank"]))
        if current else "",
        (f"{p['poll']['name']} ({p['poll']['updated']}, {p['poll']['season']}):\n" +
         _csv(p["poll"]["rows"], ["rank", "team", "record", "points", "previous"]))
        if p.get("poll") else "",
        "Roster:\n" + _csv(p["roster"], ["name", "jerseyNumber", "position", "height",
                                         "academicYear", "hometown", "active"]),
    ]
    return SYSTEM_RULES + "\n" + "\n\n".join(x for x in parts if x)


def _site(g):
    return {"home": "H", "away": "A", "neutral": "N"}.get(g.get("site"))


def _periods(game_base, period_scores, regulation=4):
    """One row per quarter. Anything past the fourth is an overtime period;
    a row that isn't two numbers is skipped rather than guessed at."""
    rows = []
    for i, part in enumerate((period_scores or "").split(",")):
        halves = part.strip().split("-")
        if len(halves) != 2:
            continue
        try:
            ku, opp = int(halves[0]), int(halves[1])
        except ValueError:
            continue
        n = i + 1
        rows.append({**game_base, "period": n,
                     "name": f"Q{n}" if n <= regulation else f"OT{n - regulation}",
                     "overtime": n > regulation, "ku_points": ku, "opp_points": opp})
    return rows


def build_pack(seed, scraped=SCRAPED):
    players = {p["name"].lower(): p for p in seed.get("players", [])}

    games, periods, ku_lines, team_totals, opp_lines, upcoming = [], [], [], [], [], []
    for g in sorted(seed.get("games", []), key=lambda x: (x["date"], x["opponent"])):
        base = {"season": g["season"], "date": g["date"], "opponent": g["opponent"],
                "opponent_key": team_key(g["opponent"])}
        if g.get("teamScore") is None:
            upcoming.append({**base, "site": _site(g), "conference": bool(g.get("conference")),
                             "exhibition": "(exh." in g["opponent"].lower(),
                             "venue": g.get("venue") or None, "city": g.get("city") or None,
                             "tip_time_local": g.get("time") or None,
                             "tv": g.get("tv") or None, "event": g.get("event") or None})
            continue
        us, them = g["teamScore"], g["opponentScore"]
        games.append({**base, "site": _site(g), "conference": bool(g.get("conference")),
                      "non_d1": bool(g.get("nonD1")),
                      "result": "W" if us > them else "L",
                      "ku_points": us, "opp_points": them, "margin": us - them,
                      "overtime": g.get("overtime"),
                      "opp_rank": g.get("opponentRank"), "opp_seed": g.get("opponentSeed"),
                      "opp_record": g.get("opponentRecord"),
                      "period_scores": g.get("periodScores"),
                      "venue": g.get("venue") or None, "city": g.get("city") or None,
                      "event": g.get("event") or None})
        periods += _periods(base, g.get("periodScores"))
        for l in g.get("lines", []):
            p = players.get(l["player"].lower(), {})
            ku_lines.append({**base, "player": l["player"],
                             "jersey": p.get("jerseyNumber"), "position": p.get("position"),
                             "started": bool(l.get("gs")), "min": l.get("min", 0),
                             **{c: l.get(c, 0) for c in STAT_COLS}})
        for side, key in (("KU", "teamStats"), ("OPP", "opponentStats")):
            if g.get(key):
                team_totals.append({**base, "side": side,
                                    **{c: g[key].get(c, 0) for c in STAT_COLS}})
        for l in g.get("opponentLines", []):
            opp_lines.append({**base, "player": l["player"], "jersey": l.get("number"),
                              "position": l.get("position"), "height": l.get("height") or None,
                              "started": bool(l.get("gs")),
                              "min": l.get("min", 0), **{c: l.get(c, 0) for c in STAT_COLS}})

    # Normalized roster key -> the school as the schedule prints it, taken
    # from the most recent fixture so the spelling matches the other tables.
    # A school on no schedule keeps its key, which is better than no name.
    opponent_display = {}
    for g in sorted(seed.get("games", []), key=lambda x: x.get("date") or ""):
        opponent_display[norm_team(g.get("opponent", ""))] = g.get("opponent", "")

    polls = seed.get("polls") or []
    current_poll = polls[-1] if polls else None
    # Read beside the seed rather than carried in it. A fixture with no
    # scraped/ gives [] for each of these, never a missing key.
    net_rows = net_table(_scraped("rankings-net.json", scraped))
    big12_rows, d1_rows = results_tables(
        _scraped("ku-index.json", scraped), _scraped("d1-results.json", scraped))
    pack = {
        "about": "Kansas Jayhawks women's basketball, from the official NCAA box scores and "
                 "kuathletics.com. One record per row; join tables on (season, date, opponent).",
        "generated_at": seed.get("generatedAt"),
        "definitions": DEFINITIONS,
        "games": games,
        "periods": periods,
        "ku_lines": ku_lines,
        "team_totals": team_totals,
        "opponent_lines": opp_lines,
        "upcoming": upcoming,
        "standings": [{**{k: st.get(k) for k in ("season", "team", "confW", "confL", "overallW",
                                                 "overallL", "nationalRank", "netRank")},
                       "team_key": team_key(st.get("team"))}
                      for st in seed.get("standings", [])],
        "poll": None if not current_poll else {
            "season": current_poll["season"], "name": current_poll.get("name") or "Poll",
            "updated": current_poll.get("updated"),
            "rows": [{**{k: r.get(k) for k in ("rank", "rankLabel", "team", "record", "points",
                                               "previous", "firstPlaceVotes")},
                      "team_key": team_key(r.get("team"))}
                     for r in current_poll.get("rows", [])]},
        "roster": [{k: p.get(k) for k in ("name", "jerseyNumber", "position", "height",
                                          "academicYear", "hometown", "lastSchool", "active")}
                   for p in seed.get("players", [])],
        # Opposing squads as their own schools list them today. Flattened to
        # one row per player with the school on it, so it joins to the other
        # tables the same way everything else here does.
        #
        # The school is carried twice on purpose. The seed stores a roster
        # under its normalized key ("arizona st"), which is what makes the
        # schedule and the box scores agree on a school — but every other
        # table in this pack names an opponent as it is printed ("Arizona
        # St."), and the instructions say to join on opponent. Shipping only
        # the key would mean a join that silently matched nothing at all.
        "opponent_roster": [
            {"opponent": opponent_display.get(t["team"], t["team"]),
             "team_key": team_key(t["team"]), "read": t.get("fetchedAt"),
             **{k: (p.get(k) or None) for k in ("name", "jerseyNumber", "position", "height",
                                                "academicYear", "hometown", "lastSchool")}}
            for t in seed.get("opponentRosters", []) for p in t.get("players", [])
        ],
        # Every Division I team's NET row. The NCAA publishes no formula behind
        # the NET, so this is the published table as it stands, not a rating
        # worked out here - and it carries the season it belongs to, because
        # until December it is last season's final.
        "team_records": net_rows,
        # Every final involving a Big 12 team, and every Division I final the
        # sweep has seen. The first is the one to reach for about the Big 12;
        # the second makes a common-opponent comparison complete.
        "big12_results": big12_rows,
        "d1_results": d1_rows,
    }
    pack["system_prompt"] = system_prompt(pack)
    return pack


def _without_timestamp(pack):
    """The pack with every trace of when it was built removed.

    Dropping the `generated_at` key is not enough: the same timestamp is
    written into the system prompt, so a pack whose data had not changed at all
    still compared as different, and the file was rewritten on every scrape.
    That is a commit, an APK rebuild and a Pages deploy, six times a day, for
    nothing.
    """
    out = {k: v for k, v in (pack or {}).items() if k != "generated_at"}
    if isinstance(out.get("system_prompt"), str):
        out["system_prompt"] = re.sub(
            r"^Data generated .*$", "Data generated <when>.",
            out["system_prompt"], flags=re.M)
    return out


def write_pack(seed, path):
    """Writes the pack; returns False when only the timestamp would change."""
    pack = build_pack(seed)
    try:
        with open(path) as f:
            old = json.load(f)
    except (OSError, ValueError):
        old = None
    if old is not None and _without_timestamp(old) == _without_timestamp(pack):
        return False
    with open(path, "w") as f:
        json.dump(pack, f, separators=(",", ":"))
        f.write("\n")
    return True


if __name__ == "__main__":
    # Rebuilding straight from the committed seed, so the published page can
    # never serve an ask-data.json older than the data the rest of it shows.
    with open("app/src/main/assets/seed.json") as f:
        SEED = json.load(f)
    P = build_pack(SEED)
    write_pack(SEED, "docs/ask-data.json")
    print(f"ask data: {len(P['games'])} games, {len(P['ku_lines'])} KU lines, "
          f"{len(P['periods'])} quarters, {len(P['opponent_lines'])} opponent lines, "
          f"{len(P['upcoming'])} scheduled, {len(P['system_prompt'])}-char prompt")
