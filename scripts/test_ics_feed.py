"""The calendar feed is consumed by phones, which are unforgiving about RFC
5545 and silent when they give up — a malformed feed just stops updating. So
the structure is checked here rather than discovered on somebody's phone.

Run with:  python3 -m unittest discover -s scripts -p 'test_*.py'
"""

import unittest
from datetime import datetime, timezone

from ics_feed import build_ics, same_apart_from_stamp

STAMP = datetime(2026, 9, 27, 12, 0, 0, tzinfo=timezone.utc)

GAMES = [
    {
        "date": "2026-11-03", "opponent": "Omaha", "season": "2026-27",
        "site": "home", "venue": "Allen Fieldhouse", "city": "Lawrence, KS",
        "time": "18:30",
    },
    {
        "date": "2026-11-14", "opponent": "Nebraska", "season": "2026-27",
        "site": "neutral", "venue": "Sanford Pentagon", "city": "Sioux Falls, SD",
        "time": "15:30", "tv": "BTN+", "event": "MarketBeat Invitational",
    },
    {
        "date": "2026-12-20", "opponent": "Oklahoma State", "season": "2026-27",
        "site": "home", "venue": "Allen Fieldhouse", "city": "Lawrence, KS",
    },
    {
        "date": "2026-11-18", "opponent": "Minnesota", "season": "2026-27",
        "site": "away", "venue": "The Barn", "city": "Minneapolis, MN",
        "teamScore": 71, "opponentScore": 64, "periodScores": "18-12, 20-16, 15-18, 18-18",
    },
]


class BuildIcsTest(unittest.TestCase):
    def setUp(self):
        self.feed = build_ics(GAMES, STAMP)
        self.lines = self.feed.split("\r\n")

    def test_calendar_is_well_formed(self):
        self.assertTrue(self.feed.startswith("BEGIN:VCALENDAR\r\n"))
        self.assertTrue(self.feed.endswith("END:VCALENDAR\r\n"))
        self.assertEqual(self.feed.count("BEGIN:VEVENT"), len(GAMES))
        self.assertEqual(self.feed.count("BEGIN:VEVENT"), self.feed.count("END:VEVENT"))
        # CRLF throughout, as the spec requires — a bare \n breaks some clients.
        self.assertNotIn("\n", self.feed.replace("\r\n", ""))

    def test_events_are_in_central_time_not_utc(self):
        # Written in the zone with a VTIMEZONE rather than converted, so the
        # November clock change does not move every later game by an hour.
        self.assertIn("BEGIN:VTIMEZONE", self.feed)
        self.assertIn("TZID:America/Chicago", self.feed)
        self.assertIn("DTSTART;TZID=America/Chicago:20261103T183000", self.lines)
        self.assertIn("DTEND;TZID=America/Chicago:20261103T203000", self.lines)

    def test_a_game_with_no_tip_time_is_all_day(self):
        self.assertIn("DTSTART;VALUE=DATE:20261220", self.lines)
        self.assertIn("DTEND;VALUE=DATE:20261221", self.lines)
        self.assertIn("TRANSP:TRANSPARENT", self.lines)

    def test_summaries_say_site_and_result(self):
        joined = "\r\n".join(self.lines)
        self.assertIn("SUMMARY:KU WBB vs Omaha", joined)
        self.assertIn("SUMMARY:KU WBB vs Nebraska", joined)       # neutral reads "vs"
        self.assertIn("SUMMARY:KU W 71-64 at Minnesota", joined)  # played

    def test_description_carries_tv_event_and_neutral(self):
        joined = "\r\n".join(self.lines)
        self.assertIn("MarketBeat Invitational", joined)
        self.assertIn("TV: BTN+", joined)
        self.assertIn("Neutral site", joined)
        self.assertIn("Tip time to be announced", joined)

    def test_special_characters_are_escaped(self):
        feed = build_ics([{
            "date": "2026-11-27", "opponent": "Miami (OH)", "season": "2026-27",
            "site": "neutral", "venue": "Hard Rock Hotel", "city": "Cancun, Mexico",
        }], STAMP)
        # A comma in a value must be escaped or it reads as a list separator.
        self.assertIn("LOCATION:Hard Rock Hotel\\, Cancun\\, Mexico", feed)

    def test_uids_are_stable_and_unique(self):
        uids = [line for line in self.lines if line.startswith("UID:")]
        self.assertEqual(len(uids), len(GAMES))
        self.assertEqual(len(set(uids)), len(GAMES))
        # Same input, same UIDs: an update must replace an event, not add one.
        again = [line for line in build_ics(GAMES, STAMP).split("\r\n")
                 if line.startswith("UID:")]
        self.assertEqual(uids, again)

    def test_long_lines_are_folded(self):
        feed = build_ics([{
            "date": "2026-11-26", "opponent": "Washington State", "season": "2026-27",
            "site": "neutral", "venue": "Hard Rock Hotel Rivera Maya Resort and Convention Centre",
            "city": "Cancun, Mexico", "event": "Cancun Challenge Riviera Division",
        }], STAMP)
        for line in feed.split("\r\n"):
            self.assertLessEqual(len(line.encode("utf-8")), 75, line)
        # Folding is only a line break: the content is still there.
        self.assertIn("Rivera Maya", feed.replace("\r\n ", ""))


class StampComparisonTest(unittest.TestCase):
    def test_a_feed_differing_only_in_timestamp_is_not_a_change(self):
        later = build_ics(GAMES, datetime(2026, 9, 28, 3, 0, 0, tzinfo=timezone.utc))
        self.assertTrue(same_apart_from_stamp(build_ics(GAMES, STAMP), later))

    def test_a_real_change_is_a_change(self):
        moved = [dict(GAMES[0], time="19:00")] + GAMES[1:]
        self.assertFalse(same_apart_from_stamp(build_ics(GAMES, STAMP),
                                               build_ics(moved, STAMP)))

    def test_a_missing_file_counts_as_a_change(self):
        self.assertFalse(same_apart_from_stamp(None, build_ics(GAMES, STAMP)))


if __name__ == "__main__":
    unittest.main()
