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
import re

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
    "height": "an opposing player's listed height, from her own school's roster page; null "
              "where that roster could not be read, which is most of them — a box score "
              "carries no height for anyone but Kansas",
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
}


# The instructions Claude is given, shared by the dashboard (docs/ask.js) and
# the app (AskEngine.kt) so both ask the same way. The tables are fetched by
# Claude's own code through the get_table tool; this only says how.
SYSTEM_RULES = """You are the analyst behind the Kansas Jayhawks women's basketball app and dashboard. You answer questions from coaches and fans about the team, using only the season data you are given.

The complete data is available to your Python code through the get_table tool: tables games, periods (one row per quarter, with each side's points), ku_lines (KU player box score lines per game), team_totals (the official team totals for both sides), opponent_lines, upcoming, standings, poll, roster, and definitions. Call it from inside code execution, for example: import json, pandas as pd; lines = pd.DataFrame(json.loads(await get_table({'table': 'ku_lines'}))). Join tables on (season, date, opponent). A summary of the smaller tables is below for orientation.

How to answer:
- Compute every number with code from the tables. Do not estimate, recall, or do arithmetic in your head, even for a simple total.
- For anything about the team as a whole — rebounds, turnovers, shooting, points — use team_totals, not the sum of ku_lines. Team rebounds (deadballs) and team turnovers belong to no individual, so the player lines add up short: KU's rebounding is about four a game higher in the official totals than in the sum of the lines.
- Lead with the answer in a sentence or two. Add a small markdown table when it helps. End with one short line saying what the figures cover (which season, which games, any filter).
- Use basketball conventions: percentages as .447, per-game rates to one decimal, records as 8-10.
- Records and site splits: the NCAA leaves out games against non-Division I opponents (non_d1), so say which convention you used when it changes the answer. The Big 12 record counts only rows where conference is true.
- Name small samples plainly (for example "only 3 games").
- If the data cannot answer the question — injuries, practice, line-ups, recruiting, play-by-play, anything not in the box scores — say so in a sentence instead of guessing.
"""


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


def build_pack(seed):
    players = {p["name"].lower(): p for p in seed.get("players", [])}

    games, periods, ku_lines, team_totals, opp_lines, upcoming = [], [], [], [], [], []
    for g in sorted(seed.get("games", []), key=lambda x: (x["date"], x["opponent"])):
        base = {"season": g["season"], "date": g["date"], "opponent": g["opponent"]}
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

    polls = seed.get("polls") or []
    current_poll = polls[-1] if polls else None
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
        "standings": [{k: st.get(k) for k in ("season", "team", "confW", "confL", "overallW",
                                              "overallL", "nationalRank", "netRank")}
                      for st in seed.get("standings", [])],
        "poll": None if not current_poll else {
            "season": current_poll["season"], "name": current_poll.get("name") or "Poll",
            "updated": current_poll.get("updated"),
            "rows": [{k: r.get(k) for k in ("rank", "rankLabel", "team", "record", "points",
                                            "previous", "firstPlaceVotes")}
                     for r in current_poll.get("rows", [])]},
        "roster": [{k: p.get(k) for k in ("name", "jerseyNumber", "position", "height",
                                          "academicYear", "hometown", "lastSchool", "active")}
                   for p in seed.get("players", [])],
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
