import os
import re
import unittest

from ask_pack import build_pack

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

SEED = {
    "generatedAt": "2026-09-26T00:00:00Z",
    "players": [{"name": "S'Mya Nichols", "jerseyNumber": "3", "position": "G", "height": "5-10",
                 "academicYear": "Junior", "hometown": "Kansas City, KS", "active": True}],
    "standings": [{"season": "2025-26", "team": "Baylor", "confW": 14, "confL": 4,
                   "overallW": 24, "overallL": 8, "nationalRank": 16, "netRank": 18}],
    "polls": [{"season": "2025-26", "name": "AP Top 25", "updated": "APR. 5, 2026",
               "rows": [{"rank": 1, "rankLabel": "1", "team": "UConn", "record": "35-2"}]}],
    "games": [
        {"date": "2025-12-07", "opponent": "Missouri St.", "season": "2025-26", "site": "home",
         "home": True, "conference": False, "teamScore": 73, "opponentScore": 70, "overtime": 1,
         "periodScores": "14-14, 15-19, 16-19, 18-11, 10-7", "opponentRecord": "6-3",
         "venue": "Allen Fieldhouse", "city": "Lawrence, KS",
         "lines": [{"player": "S'Mya Nichols", "min": 38, "fgm": 9, "fga": 20, "tpm": 2, "tpa": 6,
                    "ftm": 4, "fta": 5, "oreb": 1, "reb": 5, "ast": 6, "to": 3, "stl": 2, "blk": 0,
                    "pf": 2, "pts": 24, "gs": 1}],
         # Deliberately more rebounds than the line above: the official totals
         # include team rebounds, which belong to no player.
         "teamStats": {"fgm": 26, "fga": 62, "tpm": 5, "tpa": 17, "ftm": 16, "fta": 21,
                       "oreb": 11, "reb": 38, "ast": 14, "to": 15, "stl": 7, "blk": 2,
                       "pf": 18, "pts": 73},
         "opponentStats": {"fgm": 25, "fga": 60, "tpm": 8, "tpa": 24, "ftm": 12, "fta": 15,
                           "oreb": 9, "reb": 33, "ast": 13, "to": 12, "stl": 6, "blk": 3,
                           "pf": 19, "pts": 70},
         "opponentLines": [{"player": "Kambree Barber", "number": "14", "position": "G", "min": 19,
                            "fgm": 0, "fga": 2, "tpm": 0, "tpa": 1, "ftm": 0, "fta": 0, "oreb": 0,
                            "reb": 3, "ast": 1, "to": 1, "stl": 1, "blk": 0, "pf": 5, "pts": 0,
                            "gs": 1}]},
        {"date": "2025-12-17", "opponent": "Haskell", "season": "2025-26", "site": "home",
         "home": True, "conference": False, "nonD1": True, "teamScore": 107, "opponentScore": 39,
         "periodScores": "30-8, 27-11, 26-9, 24-11"},
        {"date": "2026-10-29", "opponent": "Washburn (exh.)", "season": "2026-27", "site": "home",
         "home": True, "conference": False, "venue": "Allen Fieldhouse", "city": "Lawrence, KS",
         "event": "Late Night in the Phog", "time": "18:30"},
        {"date": "2027-01-07", "opponent": "Iowa St.", "season": "2026-27", "site": "away",
         "home": False, "conference": True, "tv": "ESPN+"},
    ],
}


class AskPackTest(unittest.TestCase):
    def setUp(self):
        self.p = build_pack(SEED)

    def test_played_and_scheduled_are_separate_flat_tables(self):
        self.assertEqual(len(self.p["games"]), 2)
        g = self.p["games"][0]
        self.assertEqual((g["site"], g["result"], g["ku_points"], g["margin"], g["overtime"]),
                         ("H", "W", 73, 3, 1))
        self.assertEqual([u["opponent"] for u in self.p["upcoming"]],
                         ["Washburn (exh.)", "Iowa St."])
        self.assertEqual(self.p["upcoming"][0]["tip_time_local"], "18:30")
        self.assertEqual([u["exhibition"] for u in self.p["upcoming"]], [True, False])
        self.assertEqual(self.p["upcoming"][1]["site"], "A")
        # The NCAA leaves Haskell out of its records, so the flag has to survive.
        self.assertEqual([g["non_d1"] for g in self.p["games"]], [False, True])

    def test_overtime_quarters_are_named_as_overtime(self):
        rows = [r for r in self.p["periods"] if r["opponent"] == "Missouri St."]
        self.assertEqual([r["name"] for r in rows], ["Q1", "Q2", "Q3", "Q4", "OT1"])
        self.assertEqual(rows[-1]["ku_points"], 10)
        self.assertEqual([r["overtime"] for r in rows], [False, False, False, False, True])
        # The quarters must add up to the final score, or a split built from
        # them would quietly disagree with the game row.
        self.assertEqual(sum(r["ku_points"] for r in rows), 73)
        self.assertEqual(sum(r["opp_points"] for r in rows), 70)

    def test_lines_carry_roster_details_and_every_stat_column(self):
        l = self.p["ku_lines"][0]
        self.assertEqual((l["jersey"], l["position"], l["pts"], l["min"], l["started"]),
                         ("3", "G", 24, 38, True))
        self.assertEqual([t["side"] for t in self.p["team_totals"]], ["KU", "OPP"])
        self.assertEqual(self.p["opponent_lines"][0]["jersey"], "14")

    def test_team_totals_are_the_official_ones_not_the_sum_of_lines(self):
        # The whole reason the table exists: summing the lines loses the team
        # rebounds and team turnovers.
        ku = next(t for t in self.p["team_totals"] if t["side"] == "KU")
        self.assertEqual(ku["reb"], 38)
        self.assertEqual(sum(l["reb"] for l in self.p["ku_lines"]), 5)

    def test_system_prompt_is_shared_and_summarizes_the_small_tables(self):
        t = self.p["system_prompt"]
        self.assertTrue(t.startswith("You are the analyst behind"))
        self.assertIn("get_table", t)
        self.assertIn("Played games:\nseason,date,opponent,site", t)
        self.assertIn("Roster:\nname,jerseyNumber,position,height,academicYear,hometown,active", t)
        self.assertIn("AP Top 25 (APR. 5, 2026, 2025-26)", t)
        # Team totals over summed lines is the single easiest number to get
        # wrong here, so the instruction has to be in the prompt.
        self.assertIn("use team_totals, not the sum of ku_lines", t)
        # Same input, same text: the prompt cache depends on it.
        self.assertEqual(t, build_pack(SEED)["system_prompt"])

    def test_the_seasons_line_is_counted_from_the_data(self):
        t = self.p["system_prompt"]
        self.assertIn("2025-26: 2 games played", t)
        self.assertIn("2026-27: 0 games played, 2 still scheduled", t)
        self.assertIn("2026-27 is the current season", t)
        # A season with nothing played must not be answered from an older one.
        self.assertIn("it has not been played yet", t)


class TableNamesAgreeTest(unittest.TestCase):
    """The three copies of the table list have to say the same thing.

    Claude's code asks for a table by name from an enum the app and the
    dashboard each declare for themselves. A table renamed here and not there
    fails at the worst possible moment — mid-answer, as a tool error — so the
    lists are checked against each other rather than trusted.
    """

    EXPECTED = ["games", "periods", "ku_lines", "team_totals", "opponent_lines", "upcoming",
                "standings", "poll", "roster", "definitions"]

    def test_the_pack_holds_exactly_those_tables(self):
        pack = build_pack(SEED)
        self.assertEqual(sorted(t for t in self.EXPECTED if t != "poll" or pack["poll"]),
                         sorted(t for t in self.EXPECTED if pack.get(t) is not None))

    def _listed(self, path, pattern):
        with open(os.path.join(REPO, path)) as f:
            body = re.search(pattern, f.read(), re.S)
        self.assertIsNotNone(body, f"table list not found in {path}")
        return re.findall(r'"([a-z_]+)"', body.group(1))

    def test_the_dashboard_and_the_app_offer_the_same_tables(self):
        self.assertEqual(self._listed("docs/ask.js", r"const TABLES = \[(.*?)\];"), self.EXPECTED)
        self.assertEqual(
            self._listed("app/src/main/java/com/example/data/AskEngine.kt",
                         r"val TABLES = listOf\((.*?)\)\n"),
            self.EXPECTED)


if __name__ == "__main__":
    unittest.main()
