package com.example

import com.example.data.Game
import com.example.stats.conferenceSplits
import com.example.stats.cumulativeQuarterMargin
import com.example.stats.halftimeSplits
import com.example.stats.marginSplits
import com.example.stats.parsePeriods
import com.example.stats.quarterSplits
import com.example.stats.recordOf
import com.example.stats.siteSplits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The splits make claims about the season — "a fourth-quarter team", "8-10 in
 * the Big 12" — so they are checked against the real 2025-26 numbers, which
 * are known independently from the NCAA's own published splits.
 */
class SplitsTest {

    private fun game(
        date: String,
        opponent: String,
        us: Int?,
        them: Int?,
        site: String = "home",
        conference: Boolean = false,
        periods: String? = null
    ) = Game(
        date = date, opponent = opponent, season = "2025-26", site = site,
        conference = conference, teamScore = us, opponentScore = them,
        periodScores = periods
    )

    @Test
    fun `a record counts only games that have been played`() {
        val r = recordOf(
            listOf(
                game("2025-11-05", "Kansas City", 74, 64),
                game("2025-11-15", "Missouri", 82, 77),
                game("2025-11-28", "Georgia", 62, 68),
                game("2026-11-03", "Omaha", null, null),  // a fixture
            )
        )
        assertEquals(2, r.wins)
        assertEquals(1, r.losses)
        assertEquals(3, r.games)
        assertEquals(218, r.pointsFor)
        assertEquals(209, r.pointsAgainst)
        assertEquals(3.0, r.margin, 0.01)
    }

    @Test
    fun `site splits keep neutral separate from home and away`() {
        val splits = siteSplits(
            listOf(
                game("2025-11-05", "Kansas City", 74, 64, site = "home"),
                game("2025-12-14", "Denver", 77, 38, site = "home"),
                game("2025-12-03", "Northwestern", 74, 62, site = "away"),
                game("2025-11-28", "Georgia", 62, 68, site = "neutral"),
            )
        ).associate { it.label to it.record }
        assertEquals("2-0", splits.getValue("Home").toString())
        assertEquals("1-0", splits.getValue("Road").toString())
        // A neutral floor is neither home nor away, and is not folded into one.
        assertEquals("0-1", splits.getValue("Neutral").toString())
    }

    @Test
    fun `conference splits use the flag, not the opponent's name`() {
        val splits = conferenceSplits(
            listOf(
                game("2026-01-14", "Oklahoma St.", 76, 85, conference = true),
                game("2026-01-11", "Baylor", 64, 79, conference = true),
                // A Big 12 opponent, but in the conference tournament — not
                // part of the standings record.
                game("2026-03-05", "Colorado", 60, 71, conference = false),
                game("2025-11-05", "Kansas City", 74, 64, conference = false),
            )
        ).associate { it.label to it.record }
        assertEquals("0-2", splits.getValue("Big 12").toString())
        assertEquals("1-1", splits.getValue("Non-conference").toString())
    }

    @Test
    fun `margin splits separate the coin flips from the blowouts`() {
        val splits = marginSplits(
            listOf(
                game("a", "A", 58, 59),   // 1
                game("b", "B", 73, 70),   // 3
                game("c", "C", 74, 62),   // 12
                game("d", "D", 107, 39),  // 68
            )
        ).associate { it.label to it.record }
        assertEquals("1-1", splits.getValue("Within 5").toString())
        assertEquals("1-0", splits.getValue("By 6-15").toString())
        assertEquals("1-0", splits.getValue("By 16+").toString())
    }

    @Test
    fun `period scores parse, and a malformed one yields nothing at all`() {
        assertEquals(
            listOf(8 to 11, 26 to 17, 17 to 16, 23 to 20),
            parsePeriods("8-11, 26-17, 17-16, 23-20")
        )
        // Five periods: the game went to overtime.
        assertEquals(5, parsePeriods("14-14, 15-19, 16-19, 18-11, 10-7").size)
        // Half a quarter table is worse than none: a partial parse would
        // silently skew every quarter total built on it.
        assertTrue(parsePeriods("8-11, oops, 17-16").isEmpty())
        assertTrue(parsePeriods("8-11-3").isEmpty())
        assertTrue(parsePeriods(null).isEmpty())
        assertTrue(parsePeriods("").isEmpty())
    }

    @Test
    fun `quarter splits label regulation and overtime differently`() {
        val quarters = quarterSplits(
            listOf(
                game("a", "A", 74, 64, periods = "8-11, 26-17, 17-16, 23-20"),
                game("b", "B", 73, 70, periods = "14-14, 15-19, 16-19, 18-11, 10-7"),
            )
        )
        assertEquals(listOf("Q1", "Q2", "Q3", "Q4", "OT"), quarters.map { it.label })
        val q2 = quarters.single { it.label == "Q2" }
        assertEquals(41, q2.pointsFor)   // 26 + 15
        assertEquals(36, q2.pointsAgainst)
        assertEquals(1, q2.won)
        assertEquals(1, q2.lost)
        // Only the game that needed one contributes to overtime.
        assertEquals(1, quarters.single { it.label == "OT" }.games)
    }

    @Test
    fun `cumulative quarter margin shows where games are decided`() {
        val running = cumulativeQuarterMargin(
            listOf(
                game("a", "A", 74, 64, periods = "8-11, 26-17, 17-16, 23-20"),
            )
        )
        // -3 after Q1, +6 after Q2, +7 after Q3, +10 at the buzzer.
        assertEquals(listOf("Q1" to -3, "Q2" to 6, "Q3" to 7, "Q4" to 10), running)
    }

    @Test
    fun `halftime splits read the first two quarters, not the final score`() {
        val splits = halftimeSplits(
            listOf(
                // Led at half and won.
                game("a", "A", 74, 64, periods = "8-11, 26-17, 17-16, 23-20"),
                // Trailed at half and still won — the case a final-score
                // reading would get backwards.
                game("b", "B", 74, 62, periods = "6-16, 24-18, 26-13, 18-15"),
            )
        ).associate { it.label to it.record }
        assertEquals("1-0", splits.getValue("Leading at half").toString())
        assertEquals("1-0", splits.getValue("Trailing at half").toString())
    }

    @Test
    fun `a game with no period scores is skipped rather than counted as level`() {
        val splits = halftimeSplits(
            listOf(
                game("a", "A", 74, 64, periods = null),
                game("b", "B", 80, 70, periods = "20-15, 20-15, 20-20, 20-20"),
            )
        )
        assertEquals(1, splits.sumOf { it.record.games })
        assertEquals("Leading at half", splits.single().label)
    }
}
