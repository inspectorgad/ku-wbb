package com.example

import com.example.data.GameTeamStats
import com.example.stats.Efficiency
import com.example.stats.cumulativeEfficiency
import com.example.stats.efficiency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Efficiency formulas are easy to write down slightly wrong — the 0.44 in true
 * shooting, the half-credit for a three — and a wrong one is not obviously
 * wrong on screen. These check them against hand-computed values and against
 * KU's real 2025-26 season totals.
 */
class EfficiencyTest {

    @Test
    fun `effective field goal percentage gives a three the extra half basket`() {
        // 10-20 with no threes is plain 50%.
        val flat = Efficiency(10, 20, 0, 0, 0, 0, 20, 0, 0)
        assertEquals(0.500, flat.effectiveFieldGoalPct, 1e-9)
        // The same 10-20 with four threes is worth more: (10 + 2) / 20.
        val deep = Efficiency(10, 20, 4, 9, 0, 0, 24, 0, 0)
        assertEquals(0.600, deep.effectiveFieldGoalPct, 1e-9)
    }

    @Test
    fun `true shooting counts free throws at the standard 0 point 44 rate`() {
        // 20 points on 15 field goal attempts and 6 free throws:
        // 20 / (2 * (15 + 0.44*6)) = 20 / 35.28
        val e = Efficiency(7, 15, 2, 5, 4, 6, 20, 0, 0)
        assertEquals(20.0 / (2 * (15 + 0.44 * 6)), e.trueShootingPct, 1e-9)
    }

    @Test
    fun `rates describe the shot diet`() {
        val e = Efficiency(10, 20, 4, 8, 5, 6, 29, 0, 0)
        assertEquals(0.400, e.threePointAttemptRate, 1e-9)  // 8 of 20
        assertEquals(0.300, e.freeThrowRate, 1e-9)          // 6 of 20
    }

    @Test
    fun `assist to turnover is null rather than infinite when nothing is lost`() {
        assertEquals(2.0, Efficiency(0, 0, 0, 0, 0, 0, 0, 10, 5).assistToTurnover!!, 1e-9)
        assertNull(Efficiency(0, 0, 0, 0, 0, 0, 0, 10, 0).assistToTurnover)
    }

    @Test
    fun `an empty line divides by nothing rather than crashing`() {
        val e = Efficiency(0, 0, 0, 0, 0, 0, 0, 0, 0)
        assertEquals(0.0, e.effectiveFieldGoalPct, 1e-9)
        assertEquals(0.0, e.trueShootingPct, 1e-9)
        assertEquals(0.0, e.threePointAttemptRate, 1e-9)
        assertEquals(0.0, e.freeThrowRate, 1e-9)
        assertNull(e.assistToTurnover)
    }

    @Test
    fun `KU's real 2025-26 season totals produce the expected efficiency`() {
        // Season totals from the official box scores: 912-1921 FG, 206-593 3PT,
        // 560-739 FT, 2590 points. Each figure below was computed from those
        // and checked against the same arithmetic in the dashboard.
        val season = listOf(
            GameTeamStats(
                gameId = 1, opponent = false,
                fgm = 912, fga = 1921, tpm = 206, tpa = 593, ftm = 560, fta = 739,
                ast = 523, to = 583, pts = 2590
            )
        )
        val e = season.efficiency()
        assertEquals(0.5284, e.effectiveFieldGoalPct, 0.0001)
        assertEquals(0.5765, e.trueShootingPct, 0.0001)
        assertEquals(0.3087, e.threePointAttemptRate, 0.0001)
        assertEquals(0.3847, e.freeThrowRate, 0.0001)
        assertEquals(0.8971, e.assistToTurnover!!, 0.0001)
        // A team that shoots 47.5% from the floor scores like a better one once
        // its threes and free throws are counted — which is the whole point.
        assertEquals(912.0 / 1921, 0.4748, 0.0001)
        assert(e.effectiveFieldGoalPct > 912.0 / 1921)
    }

    @Test
    fun `the cumulative walk settles toward the season figure`() {
        val games = listOf(
            // A hot opening night, then two ordinary ones.
            GameTeamStats(gameId = 1, opponent = false, fgm = 12, fga = 20, tpm = 6, tpa = 10, pts = 30),
            GameTeamStats(gameId = 2, opponent = false, fgm = 8, fga = 20, tpm = 1, tpa = 6, pts = 17),
            GameTeamStats(gameId = 3, opponent = false, fgm = 8, fga = 20, tpm = 1, tpa = 6, pts = 17),
        )
        val walk = cumulativeEfficiency(games)
        assertEquals(3, walk.size)
        // Game one alone: (12 + 3) / 20.
        assertEquals(0.750, walk[0].effectiveFieldGoalPct, 1e-9)
        // All three: (28 + 4) / 60 — the opening night stops dominating.
        assertEquals(32.0 / 60.0, walk[2].effectiveFieldGoalPct, 1e-9)
        // It is a running figure, so it moves less with each added game.
        val firstStep = kotlin.math.abs(walk[1].effectiveFieldGoalPct - walk[0].effectiveFieldGoalPct)
        val secondStep = kotlin.math.abs(walk[2].effectiveFieldGoalPct - walk[1].effectiveFieldGoalPct)
        assert(secondStep < firstStep)
    }
}
