import unittest

from seed_text import fix_mojibake, norm_name, norm_team


class FixMojibake(unittest.TestCase):
    """Names the NCAA feed delivered corrupted, and what must come back."""

    def test_repairs_the_common_latin_1_case(self):
        self.assertEqual(fix_mojibake("AnaÃ«lle Dutat"), "Anaëlle Dutat")

    def test_repairs_a_name_carrying_a_euro_sign(self):
        # The case that was shipping wrong. Byte 0xA4 comes back as €, which
        # has no Latin-1 encoding at all, so the repair raised and gave up —
        # leaving this name visibly mangled in the Colorado box score.
        self.assertEqual(fix_mojibake("Isa HÃ€mÃ€lÃ€inen"), "Isa Hämäläinen")

    def test_does_not_take_the_cp1252_reading_of_a_euro_sign(self):
        # cp1252 can also encode €, to a different byte, and would rebuild this
        # as "HÀmÀlÀinen" — wrong, and clean enough to pass the sanity check.
        # Codec order is what prevents it, so pin the outcome.
        self.assertNotIn("À", fix_mojibake("Isa HÃ€mÃ€lÃ€inen"))

    def test_repairs_a_name_carrying_a_caron(self):
        self.assertEqual(fix_mojibake("Å½iva Labinjan"), "Živa Labinjan")

    def test_leaves_clean_names_alone(self):
        for name in ("Anaëlle Dutat", "S'Mya Nichols", "Živa Labinjan", ""):
            self.assertEqual(fix_mojibake(name), name)
        self.assertIsNone(fix_mojibake(None))

    def test_passes_through_what_it_cannot_repair(self):
        # A name mangled further by a bad guess stops matching that player's
        # other rows, which is worse than showing the wrong letters.
        self.assertEqual(fix_mojibake("Ãx"), "Ãx")


class NormName(unittest.TestCase):

    def test_folds_accents_case_and_punctuation(self):
        self.assertEqual(norm_name("Anaëlle Dutat"), norm_name("ANAELLE DUTAT"))
        self.assertEqual(norm_name("D'Shara Booker"), norm_name("DShara Booker"))

    def test_mojibake_happens_to_fold_to_the_same_key(self):
        # Not by design: NFKD decomposes Ã to A plus a combining tilde, the
        # mark is stripped, and € is dropped as a non-letter. It holds for the
        # a-family and not for others, which is why names are repaired before
        # they are keyed rather than relying on this.
        self.assertEqual(norm_name("Isa HÃ€mÃ€lÃ€inen"), norm_name("Isa Hämäläinen"))

    def test_distinct_players_stay_distinct(self):
        self.assertNotEqual(norm_name("Kyndall Hunter"), norm_name("Jemma Hunter"))


class NormTeam(unittest.TestCase):

    def test_state_and_st_are_one_school(self):
        self.assertEqual(norm_team("South Dakota State"), norm_team("South Dakota St."))
        self.assertEqual(norm_team("Iowa State"), norm_team("Iowa St."))

    def test_the_word_university_is_dropped(self):
        # Which is why an opponent-sites entry for this school is keyed "lamar"
        # — spelling it out there would never be looked up.
        self.assertEqual(norm_team("Lamar University"), "lamar")

    def test_poll_votes_are_stripped(self):
        self.assertEqual(norm_team("Baylor (12)"), "baylor")

    def test_an_exhibition_keeps_its_suffix(self):
        # These are a different fixture from a regular-season meeting and must
        # not collapse onto one; it also means a sites entry would need the
        # suffix to match, and an exhibition has no box score to attach to.
        self.assertEqual(norm_team("Northeastern State (exh.)"), "northeastern st (exh)")


if __name__ == "__main__":
    unittest.main()
