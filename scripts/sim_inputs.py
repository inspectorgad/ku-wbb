"""What the season simulator needs, worked out once a night.

The simulation itself runs on the reader's device - docs/sim.js on the
dashboard - and not here, which is what lets a "what if Kansas wins this one?"
answer at once. This builds its inputs from what the pipeline already has: the
win model's chance for every game Kansas has left, the Big 12 table, and each
team's rating.

Modelled on the same file in the volleyball app, but basketball differs in two
ways that matter more than the resemblance:

- The automatic NCAA place goes to the Big 12 TOURNAMENT winner, not the
  regular-season champion, so the tournament has to be played out as a bracket
  rather than read off the table. All 16 teams are in it, seeds 1-4 get a
  double bye and 5-8 a single one, so a top-four finish is worth two rounds and
  the simulation has to know where Kansas lands, not just whether it won.
- There is no projected NET here, and so no "chance of making the NCAA
  tournament" beyond winning the Big 12's automatic place. Volleyball can
  recompute its RPI from simulated results because the RPI is arithmetic the
  NCAA publishes. The NET is not: the NCAA's own FAQ says its components are
  machine-learned "in such a way that there is no static formula for them".
  A number here would be invention dressed as arithmetic, so there isn't one.

Two simplifications, both said on screen:

- The other teams' remaining opponents are not published game by game, so
  their games other than against Kansas are played against an average Big 12
  opponent: each won with the chance the win model gives that team on a
  neutral floor against the conference's mean rating.
- Ties in the table are settled by a coin toss. The Big 12's real tiebreakers
  start with head-to-head, which needs results this simulation does not keep.
"""

# Big 12 women's basketball: 16 teams, each playing 18 conference games. That
# is not a round robin - 16 teams would need 30 - so two teams on the same
# record have not played the same schedule.
CONF_TOTAL = 18
CONF_TEAMS = 16


def build(*, ku_key, remaining, standings, season, model, ku_z, norm,
          conf_total=CONF_TOTAL):
    """The simulator's inputs, or None when there is nothing left to simulate.

    remaining: Kansas's unplayed games, the seed's own dicts, each with date,
        opponent, site, conference and (when the model could rate it)
        winProbability.
    standings: every standings row, ALL SEASONS - filtered here to `season`.
        Keying by team without that filter silently collapses two seasons onto
        one row.
    model: a win_model.Model. ku_z: Kansas's rating, already shrunk.
    """
    if not remaining or model is None or ku_z is None or not season:
        return None

    # One season only. The seed carries a row per team per season.
    rows = {norm(r["team"]): r for r in standings if r.get("season") == season}
    ku_row = rows.get(ku_key)
    if ku_row is None or len(rows) < 2:
        return None

    # rating_of returns (z, source); the rating is the first half.
    def rating(team_key):
        z, _source = model.rating_of(team_key)
        return z

    rated = [z for z in (rating(k) for k in rows) if z is not None]
    mean = sum(rated) / len(rated) if rated else 0.0

    # A game the model could not rate cannot be simulated. That covers the
    # "Big 12 Tournament" placeholder on the schedule, which is a bracket this
    # simulation plays out properly rather than a game against a team.
    playable = [g for g in remaining if g.get("winProbability") is not None]
    skipped = [g["opponent"] for g in remaining if g.get("winProbability") is None]
    if not playable:
        return None

    ku_conf_left = {}
    for g in playable:
        if g.get("conference"):
            k = norm(g["opponent"])
            ku_conf_left[k] = ku_conf_left.get(k, 0) + 1

    others = []
    for k, r in rows.items():
        if k == ku_key:
            continue
        played = r["confW"] + r["confL"]
        z = rating(k)
        z = mean if z is None else z
        others.append({
            "team": r["team"],
            "key": k,
            "confW": r["confW"],
            "confL": r["confL"],
            # Their conference games still to play, less the ones against
            # Kansas, which the simulation plays out as Kansas's own.
            "remaining": max(0, conf_total - played - ku_conf_left.get(k, 0)),
            "pVsAverage": round(model.probability(z, mean, "neutral"), 4),
            "rating": round(z, 4),
        })

    return {
        "season": season,
        "confTotal": conf_total,
        "confTeams": CONF_TEAMS,
        # The bracket needs a chance for games Kansas is not in, so the engine
        # recomputes from ratings rather than being handed every pairing.
        "scale": model.scale,
        "kansas": {
            "confW": ku_row["confW"], "confL": ku_row["confL"],
            "overallW": ku_row["overallW"], "overallL": ku_row["overallL"],
            "rating": round(ku_z, 4),
        },
        "remaining": [
            {"date": g["date"], "opponent": g["opponent"],
             "site": g.get("site") or "neutral",
             "conference": bool(g.get("conference")),
             "key": norm(g["opponent"]),
             "p": round(g["winProbability"], 4)}
            for g in playable
        ],
        "others": others,
        # Said on screen so a record that looks short is explained rather than
        # looking like a missing game.
        "unrated": sorted(set(skipped)),
    }
