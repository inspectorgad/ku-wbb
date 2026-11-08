import json
import os
import unittest

from win_model import Model, blom_z, chance, fit_line

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CONFIG = json.load(open(os.path.join(REPO, "scripts/win-model.json")))

# A toy league: team "a" is 1st, "b" 2nd, and so on down to 363rd.
LEAGUE = {f"t{i}": i for i in range(1, 364)}


class BlomTest(unittest.TestCase):
    def test_the_ncaas_own_anchor(self):
        """The NCAA puts a 90th-percentile team at "approximately 36 in NET"."""
        from statistics import NormalDist
        pct = NormalDist().cdf(blom_z(36, 363))
        self.assertAlmostEqual(pct, 0.902, places=3)

    def test_the_middle_of_the_table_is_average(self):
        self.assertAlmostEqual(blom_z(182, 363), 0.0, places=2)

    def test_stronger_teams_rate_higher(self):
        zs = [blom_z(r, 363) for r in (1, 25, 100, 200, 300, 363)]
        self.assertEqual(zs, sorted(zs, reverse=True))

    def test_the_top_is_further_apart_than_the_middle(self):
        """The whole reason the rank is transformed rather than used raw."""
        top = blom_z(1, 363) - blom_z(11, 363)
        middle = blom_z(175, 363) - blom_z(185, 363)
        self.assertGreater(top, 3 * middle)

    def test_nonsense_ranks_are_refused_rather_than_guessed(self):
        self.assertIsNone(blom_z(0, 363))
        self.assertIsNone(blom_z(None, 363))


class ShippedConstantsTest(unittest.TestCase):
    """The constants are measured, so the tests say what they were measured to be.

    If someone retunes them, these fail and say which published figure the new
    numbers no longer match - better than the model quietly drifting.
    """

    def test_two_even_teams_one_at_home(self):
        """All 363 teams went 3028-2029 at home in 2025-26: 59.9%."""
        m = Model(CONFIG, LEAGUE)
        p = m.probability(0.0, 0.0, "home")
        self.assertAlmostEqual(p, 0.599, places=2)

    def test_a_neutral_floor_is_even(self):
        m = Model(CONFIG, LEAGUE)
        self.assertAlmostEqual(m.probability(0.0, 0.0, "neutral"), 0.5, places=6)

    def test_home_and_away_are_mirror_images(self):
        m = Model(CONFIG, LEAGUE)
        self.assertAlmostEqual(
            m.probability(0.0, 0.0, "home") + m.probability(0.0, 0.0, "away"), 1.0, places=6)

    def test_an_unknown_site_is_treated_as_neutral(self):
        m = Model(CONFIG, LEAGUE)
        self.assertAlmostEqual(m.probability(0.0, 0.0, None), 0.5, places=6)


class ModelTest(unittest.TestCase):
    def setUp(self):
        self.m = Model(CONFIG, LEAGUE)

    def test_venue_ordering(self):
        ku, opp = 1.0, 0.5
        home = self.m.probability(ku, opp, "home")
        neutral = self.m.probability(ku, opp, "neutral")
        away = self.m.probability(ku, opp, "away")
        self.assertGreater(home, neutral)
        self.assertGreater(neutral, away)

    def test_a_weaker_opponent_is_a_better_chance(self):
        ps = [self.m.probability(1.0, z, "home") for z in (2.5, 1.5, 0.5, -0.5, -1.5)]
        self.assertEqual(ps, sorted(ps))

    def test_probabilities_stay_in_range(self):
        for ku in (-3.0, 0.0, 3.0):
            for opp in (-3.0, 0.0, 3.0):
                for site in ("home", "away", "neutral"):
                    p = self.m.probability(ku, opp, site)
                    self.assertGreater(p, 0.0)
                    self.assertLess(p, 1.0)

    def test_the_poll_outranks_the_net(self):
        """A ranked team is rated from this season's poll, not last season's NET."""
        m = Model(CONFIG, LEAGUE, poll_share={"t300": 0.9}, net_weight=0.75)
        z, source = m.rating_of("t300")
        self.assertEqual(source, "poll")
        self.assertGreater(z, 1.0)          # the poll says strong
        self.assertEqual(m.rating_of("t5")[1], "net")

    def test_an_unrated_team_is_refused_rather_than_guessed(self):
        self.assertEqual(self.m.rating_of("Big 12 Tournament"), (None, None))

    def test_stale_ratings_are_pulled_toward_a_coin_toss(self):
        """Shrinking last season's NET should make every forecast less certain."""
        fresh = Model(CONFIG, LEAGUE, net_weight=1.0)
        stale = Model(CONFIG, LEAGUE, net_weight=CONFIG["staleNetWeight"])
        ku_f, _ = fresh.rating_of("t50")
        op_f, _ = fresh.rating_of("t300")
        ku_s, _ = stale.rating_of("t50")
        op_s, _ = stale.rating_of("t300")
        self.assertGreater(fresh.probability(ku_f, op_f, "home"),
                           stale.probability(ku_s, op_s, "home"))

    def test_the_shrink_reaches_poll_ratings_too(self):
        """Shrinking one side only would subtract two different scales.

        The poll line is fitted against NET z values, so a poll rating is a
        reading on the NET's scale and shrinks with it. When this was applied
        to the NET branch alone, Kansas (rated from the NET) was shrunk while
        every ranked opponent was not, and the shipped forecast against
        Oklahoma State read 21% where a consistent shrink gives 39% - the
        shrink pushing a game AWAY from a coin toss, which is the opposite of
        what it is for.
        """
        shares = {"t300": 0.9}
        full = Model(CONFIG, LEAGUE, poll_share=shares, net_weight=1.0)
        half = Model(CONFIG, LEAGUE, poll_share=shares, net_weight=0.5)
        self.assertAlmostEqual(half.rating_of("t300")[0],
                               0.5 * full.rating_of("t300")[0], places=9)

    def test_shrinking_pulls_a_mixed_pair_toward_a_coin_toss(self):
        """The property the shrink exists for, across the two rating sources.

        test_stale_ratings_are_pulled_toward_a_coin_toss only ever exercised
        NET against NET, which is why it passed throughout the bug.
        """
        shares = {"t300": 0.9}                      # a strong ranked opponent
        fresh = Model(CONFIG, LEAGUE, poll_share=shares, net_weight=1.0)
        stale = Model(CONFIG, LEAGUE, poll_share=shares, net_weight=0.75)
        for site in ("home", "away", "neutral"):
            p_fresh = fresh.probability(fresh.rating_of("t50")[0],
                                        fresh.rating_of("t300")[0], site)
            p_stale = stale.probability(stale.rating_of("t50")[0],
                                        stale.rating_of("t300")[0], site)
            self.assertLess(abs(p_stale - 0.5), abs(p_fresh - 0.5),
                            f"shrinking moved {site} away from a coin toss")

    def test_the_poll_is_refitted_only_against_a_current_net(self):
        shares = {f"t{i}": 1.0 - i / 100 for i in range(1, 26)}
        stale = Model(CONFIG, LEAGUE, poll_share=shares, net_weight=0.75)
        self.assertIn("win-model.json", stale.fit_note)
        fresh = Model(CONFIG, LEAGUE, poll_share=shares, net_weight=1.0)
        self.assertIn("refitted", fresh.fit_note)


class FitLineTest(unittest.TestCase):
    def test_recovers_a_line(self):
        intercept, slope = fit_line([(x, 3 + 2 * x) for x in range(10)])
        self.assertAlmostEqual(intercept, 3.0)
        self.assertAlmostEqual(slope, 2.0)

    def test_refuses_too_little_to_go_on(self):
        self.assertIsNone(fit_line([(1, 1), (2, 2)]))
        self.assertIsNone(fit_line([(1, 1), (1, 2), (1, 3)]))


class ChanceTest(unittest.TestCase):
    def test_no_game_is_certain(self):
        self.assertEqual(chance(0.9999), "over 99%")
        self.assertEqual(chance(1.0), "over 99%")
        self.assertEqual(chance(0.0001), "under 1%")
        self.assertEqual(chance(0.0), "under 1%")

    def test_ordinary_numbers(self):
        self.assertEqual(chance(0.37), "37%")
        self.assertEqual(chance(0.5), "50%")
        self.assertEqual(chance(0.9125), "91%")

    def test_halves_round_up_not_to_even(self):
        """Python's round() goes half-to-even; the app's roundHalfUp does not.

        0.485 lands on exactly 48.5 as a double, so the two conventions give
        48 and 49 for the same game. Same cases as WinChanceTest.kt.
        """
        self.assertEqual(chance(0.485), "49%")
        self.assertEqual(chance(0.4849), "48%")

    def test_nothing_to_say(self):
        self.assertEqual(chance(None), "")

    def test_the_top_boundary_does_not_print_a_certainty(self):
        """0.995 * 100 + 0.5 is exactly 100.0, so a strict > printed "100%".

        The one value that could reach the end of the function and claim a
        certainty. Same case as WinChanceTest.kt.
        """
        self.assertEqual(chance(0.995), "over 99%")
        self.assertEqual(chance(0.9949), "99%")
        # The low end needs no such guard: it rounds to 1%, never 0%.
        self.assertEqual(chance(0.005), "1%")


if __name__ == "__main__":
    unittest.main()
