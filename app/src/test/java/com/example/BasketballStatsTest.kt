package com.example

import com.example.data.StatLine
import com.example.stats.aggregate
import com.example.stats.formatPct
import com.example.stats.formatPerGame
import com.example.stats.formatShots
import com.example.stats.summarize
import org.junit.Assert.assertEquals
import org.junit.Test

class BasketballStatsTest {

    private fun line(
        minutes: Int = 0,
        fgm: Int = 0,
        fga: Int = 0,
        tpm: Int = 0,
        tpa: Int = 0,
        ftm: Int = 0,
        fta: Int = 0,
        oreb: Int = 0,
        reb: Int = 0,
        ast: Int = 0,
        to: Int = 0,
        stl: Int = 0,
        blk: Int = 0,
        pf: Int = 0,
        pts: Int = 0,
        started: Boolean = false
    ) = StatLine(
        playerId = 1, gameId = 1,
        minutes = minutes, fieldGoalsMade = fgm, fieldGoalsAttempted = fga,
        threePointsMade = tpm, threePointsAttempted = tpa,
        freeThrowsMade = ftm, freeThrowsAttempted = fta,
        offensiveRebounds = oreb, totalRebounds = reb, assists = ast,
        turnovers = to, steals = stl, blocks = blk, personalFouls = pf,
        points = pts, started = started
    )

    @Test
    fun `empty aggregation is all zeros and safe to divide`() {
        val totals = aggregate(emptyList())
        assertEquals(0, totals.games)
        assertEquals(0.0, totals.fieldGoalPercentage, 0.0)
        assertEquals(0.0, totals.pointsPerGame, 0.0)
        assertEquals(0.0, totals.freeThrowPercentage, 0.0)
    }

    @Test
    fun `aggregation sums counting stats across games`() {
        val totals = aggregate(
            listOf(
                line(minutes = 29, fgm = 7, fga = 15, tpm = 1, tpa = 4, ftm = 6, fta = 6,
                    oreb = 1, reb = 4, ast = 6, to = 2, stl = 1, pts = 21, started = true),
                line(minutes = 31, fgm = 6, fga = 12, tpm = 2, tpa = 5, ftm = 4, fta = 5,
                    oreb = 2, reb = 7, ast = 4, to = 3, blk = 1, pts = 18, started = true),
                line(minutes = 18, fgm = 3, fga = 8, ftm = 1, fta = 2, reb = 5, stl = 2, pts = 7)
            )
        )
        assertEquals(3, totals.games)
        assertEquals(2, totals.gamesStarted)
        assertEquals(78, totals.minutes)
        assertEquals(16, totals.fieldGoalsMade)
        assertEquals(35, totals.fieldGoalsAttempted)
        assertEquals(3, totals.threePointsMade)
        assertEquals(9, totals.threePointsAttempted)
        assertEquals(11, totals.freeThrowsMade)
        assertEquals(13, totals.freeThrowsAttempted)
        assertEquals(3, totals.offensiveRebounds)
        assertEquals(16, totals.totalRebounds)
        assertEquals(10, totals.assists)
        assertEquals(5, totals.turnovers)
        assertEquals(3, totals.steals)
        assertEquals(1, totals.blocks)
        assertEquals(46, totals.points)
    }

    @Test
    fun `shooting percentages are made over attempted`() {
        val totals = aggregate(listOf(line(fgm = 9, fga = 20, tpm = 3, tpa = 8, ftm = 5, fta = 6)))
        assertEquals(0.45, totals.fieldGoalPercentage, 1e-9)
        assertEquals(0.375, totals.threePointPercentage, 1e-9)
        assertEquals(5.0 / 6.0, totals.freeThrowPercentage, 1e-9)
        assertEquals(".450", formatPct(totals.fieldGoalPercentage))
        assertEquals(".375", formatPct(totals.threePointPercentage))
    }

    @Test
    fun `per-game rates divide by games played`() {
        val totals = aggregate(
            listOf(
                line(pts = 20, reb = 10, ast = 6, stl = 3, blk = 2, minutes = 30),
                line(pts = 15, reb = 6, ast = 2, stl = 1, blk = 0, minutes = 26)
            )
        )
        assertEquals(17.5, totals.pointsPerGame, 1e-9)
        assertEquals(8.0, totals.reboundsPerGame, 1e-9)
        assertEquals(4.0, totals.assistsPerGame, 1e-9)
        assertEquals(2.0, totals.stealsPerGame, 1e-9)
        assertEquals(1.0, totals.blocksPerGame, 1e-9)
        assertEquals(28.0, totals.minutesPerGame, 1e-9)
        assertEquals("17.5", formatPerGame(totals.pointsPerGame))
    }

    @Test
    fun `formatPct uses basketball notation`() {
        assertEquals(".000", formatPct(0.0))
        assertEquals(".333", formatPct(1.0 / 3.0))
        assertEquals(".500", formatPct(0.5))
        assertEquals("1.000", formatPct(1.0))
    }

    @Test
    fun `formatShots is made-attempted`() {
        assertEquals("7-15", formatShots(7, 15))
        assertEquals("0-0", formatShots(0, 0))
    }

    @Test
    fun `summarize builds a readable game line`() {
        val summary = summarize(
            line(minutes = 29, fgm = 7, fga = 15, pts = 21, reb = 4, ast = 6, stl = 1, blk = 2)
        )
        assertEquals("21 PTS, 4 REB, 6 AST, 1 STL, 2 BLK", summary)
    }

    @Test
    fun `summarize handles bench and scoreless lines`() {
        assertEquals("No stats", summarize(line()))
        assertEquals("3 min played", summarize(line(minutes = 3)))
        assertEquals("0 PTS, 2 REB", summarize(line(minutes = 8, fga = 2, reb = 2)))
    }
}
