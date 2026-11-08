import json
import os
import unittest

from sim_inputs import build, CONF_TOTAL, CONF_TEAMS
from win_model import Model

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CONFIG = json.load(open(os.path.join(REPO, "scripts/win-model.json")))

# Sixteen conference teams inside a full Division I table, so blom_z has a
# sensible field to work against.
LEAGUE = {f"t{i}": i for i in range(1, 364)}
CONF = ["kansas"] + [f"t{i}" for i in range(1, 16)]
for i, key in enumerate(CONF):
    LEAGUE[key] = 20 + i * 5


def norm(s):
    return (s or "").strip().lower()


def standings(season, teams=CONF, confW=0, confL=0):
    return [{"season": season, "team": t, "confW": confW, "confL": confL,
             "overallW": 5, "overallL": 2} for t in teams]


def game(opponent, conference=True, p=0.6, site="home", date="2027-01-05"):
    g = {"date": date, "opponent": opponent, "site": site, "conference": conference}
    if p is not None:
        g["winProbability"] = p
    return g


def model():
    return Model(CONFIG, LEAGUE, net_weight=1.0)


def inputs(**over):
    kw = dict(
        ku_key="kansas",
        remaining=[game("t1"), game("t2"), game("t3", conference=False)],
        standings=standings("2026-27"),
        season="2026-27",
        model=model(),
        ku_z=model().rating_of("kansas")[0],
        norm=norm,
    )
    kw.update(over)
    return build(**kw)


class ShapeTest(unittest.TestCase):
    def test_the_whole_conference_comes_through(self):
        s = inputs()
        self.assertEqual(len(s["others"]) + 1, CONF_TEAMS)
        self.assertEqual(s["confTotal"], CONF_TOTAL)
        self.assertEqual(s["season"], "2026-27")
        self.assertIn("scale", s)          # the bracket needs it

    def test_every_other_team_is_rated(self):
        """The bracket plays teams off against each other, so none may be None."""
        for o in inputs()["others"]:
            self.assertIsInstance(o["rating"], float)
            self.assertGreater(o["pVsAverage"], 0.0)
            self.assertLess(o["pVsAverage"], 1.0)

    def test_nothing_left_to_play(self):
        self.assertIsNone(inputs(remaining=[]))

    def test_no_ratings_yet(self):
        self.assertIsNone(inputs(model=None))
        self.assertIsNone(inputs(ku_z=None))

    def test_kansas_missing_from_the_table(self):
        self.assertIsNone(inputs(standings=standings("2026-27", teams=["t1", "t2"])))


class SeasonFilterTest(unittest.TestCase):
    """The trap: ku-wbb's standings carry a row per team PER SEASON."""

    def test_only_the_forecast_season_is_used(self):
        both = standings("2025-26", confW=9, confL=9) + standings("2026-27", confW=1, confL=0)
        s = build(ku_key="kansas", remaining=[game("t1")], standings=both,
                  season="2026-27", model=model(),
                  ku_z=model().rating_of("kansas")[0], norm=norm)
        self.assertEqual(s["kansas"]["confW"], 1)
        self.assertEqual(len(s["others"]), CONF_TEAMS - 1)
        # Keyed by team alone, last-one-wins would have left 2025-26's 9-9.
        for o in s["others"]:
            self.assertEqual((o["confW"], o["confL"]), (1, 0))

    def test_a_season_with_no_rows_is_not_simulated(self):
        self.assertIsNone(inputs(standings=standings("2025-26")))


class RemainingGamesTest(unittest.TestCase):
    def test_an_unratable_game_is_left_out_and_named(self):
        s = inputs(remaining=[game("t1"), game("Big 12 Tournament", p=None)])
        self.assertEqual(len(s["remaining"]), 1)
        self.assertEqual(s["unrated"], ["Big 12 Tournament"])

    def test_nothing_ratable_at_all(self):
        self.assertIsNone(inputs(remaining=[game("Big 12 Tournament", p=None)]))

    def test_the_site_survives(self):
        """Volleyball stores H/A; here it is home/away/neutral and neutral is common."""
        s = inputs(remaining=[game("t1", site="neutral"), game("t2", site="away")])
        self.assertEqual([g["site"] for g in s["remaining"]], ["neutral", "away"])

    def test_a_missing_site_is_treated_as_neutral(self):
        g = game("t1")
        del g["site"]
        self.assertEqual(inputs(remaining=[g])["remaining"][0]["site"], "neutral")

    def test_others_lose_the_games_kansas_still_plays_them(self):
        # Kansas visits t1 twice and t2 once; all three are conference games.
        s = inputs(remaining=[game("t1"), game("t1", date="2027-02-05"), game("t2")])
        by = {o["key"]: o for o in s["others"]}
        self.assertEqual(by["t1"]["remaining"], CONF_TOTAL - 2)
        self.assertEqual(by["t2"]["remaining"], CONF_TOTAL - 1)
        self.assertEqual(by["t5"]["remaining"], CONF_TOTAL)

    def test_a_non_conference_game_does_not_touch_the_table(self):
        s = inputs(remaining=[game("t1", conference=False)])
        by = {o["key"]: o for o in s["others"]}
        self.assertEqual(by["t1"]["remaining"], CONF_TOTAL)
        self.assertFalse(s["remaining"][0]["conference"])

    def test_remaining_never_goes_negative(self):
        s = inputs(standings=standings("2026-27", confW=CONF_TOTAL, confL=0))
        for o in s["others"]:
            self.assertGreaterEqual(o["remaining"], 0)


class RatingTest(unittest.TestCase):
    def test_rating_of_returns_a_pair_and_only_the_z_is_kept(self):
        """win_model.rating_of gives (z, source); a tuple here breaks the maths."""
        z, source = model().rating_of("kansas")
        self.assertIsInstance(source, str)
        self.assertAlmostEqual(inputs()["kansas"]["rating"], round(z, 4))

    def test_an_unrated_conference_team_falls_back_to_the_mean(self):
        rows = standings("2026-27") + [
            {"season": "2026-27", "team": "Nobody", "confW": 0, "confL": 0,
             "overallW": 1, "overallL": 1}]
        s = build(ku_key="kansas", remaining=[game("t1")], standings=rows,
                  season="2026-27", model=model(),
                  ku_z=model().rating_of("kansas")[0], norm=norm)
        nobody = next(o for o in s["others"] if o["key"] == "nobody")
        self.assertIsInstance(nobody["rating"], float)
        self.assertAlmostEqual(nobody["pVsAverage"], 0.5, places=6)


class ShippedSeedTest(unittest.TestCase):
    """The real seed, so the committed inputs are checked, not just the shapes."""

    def setUp(self):
        seed = json.load(open(os.path.join(REPO, "app/src/main/assets/seed.json")))
        self.sim = seed.get("simulation")

    def test_the_seed_carries_a_simulation(self):
        self.assertIsNotNone(self.sim, "update-seed.py did not write seed['simulation']")

    def test_it_is_a_whole_big_12(self):
        self.assertEqual(len(self.sim["others"]) + 1, CONF_TEAMS)
        self.assertEqual(self.sim["confTotal"], CONF_TOTAL)

    def test_every_remaining_game_has_a_chance_and_a_site(self):
        for g in self.sim["remaining"]:
            self.assertTrue(0.0 < g["p"] < 1.0, g)
            self.assertIn(g["site"], ("home", "away", "neutral"), g)

    def test_the_tournament_placeholder_is_not_played_as_a_game(self):
        names = [g["opponent"] for g in self.sim["remaining"]]
        self.assertNotIn("Big 12 Tournament", names)

    def test_nobody_has_more_conference_games_left_than_exist(self):
        for o in self.sim["others"]:
            self.assertLessEqual(o["remaining"], CONF_TOTAL)
            self.assertGreaterEqual(o["remaining"], 0)


if __name__ == "__main__":
    unittest.main()
