package com.example

import com.example.data.Game
import com.example.data.GameTeamStats
import com.example.data.OpponentStatLine
import com.example.data.normTeam
import com.example.data.sameTeam
import com.example.stats.aggregateOpponentLines
import com.example.stats.opponentPlayers
import com.example.stats.summarizeOpponents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Opponents tab says what one team did *against Kansas*, which is the only
 * thing the box scores can support. These check the two ways that goes wrong:
 * a school whose name is spelled two ways splitting into two rows, and a
 * record stated from the wrong side.
 */
class OpponentsTest {

    private var nextId = 1L

    private fun game(
        date: String, opponent: String, us: Int?, them: Int?, season: String = "2025-26"
    ) = Game(
        id = nextId++, date = date, opponent = opponent, season = season,
        site = "home", teamScore = us, opponentScore = them
    )

    private fun teamRow(gameId: Long, opponent: Boolean, pts: Int, fgm: Int = 0, fga: Int = 0,
                        tpm: Int = 0) =
        GameTeamStats(gameId = gameId, opponent = opponent, pts = pts, fgm = fgm, fga = fga, tpm = tpm)

    private fun oppLine(
        gameId: Long, name: String, pts: Int, reb: Int = 0, jersey: String = "", position: String = "",
        started: Boolean = false
    ) = OpponentStatLine(
        gameId = gameId, playerName = name, jerseyNumber = jersey, position = position,
        points = pts, rebounds = reb, started = started
    )

    @Test
    fun `two spellings of one school are one opponent`() {
        // The schedule says "South Dakota State"; the box score says
        // "South Dakota St.". They are the same team and must be one row.
        val games = listOf(
            game("2025-11-20", "South Dakota St.", 70, 64),
            game("2025-12-30", "South Dakota State", 60, 72),
        )
        val summaries = summarizeOpponents(games, emptyList())
        assertEquals(1, summaries.size)
        // Titled with the most recent spelling, not whichever came first.
        assertEquals("South Dakota State", summaries.single().name)
        assertEquals(2, summaries.single().meetings)
    }

    @Test
    fun `the record is stated from the opponent's side`() {
        val games = listOf(
            game("2025-11-20", "Baylor", 70, 64),   // KU won: Baylor 0-1
            game("2026-01-11", "Baylor", 64, 79),   // KU lost: Baylor 1-1
        )
        val s = summarizeOpponents(games, emptyList()).single()
        assertEquals(1, s.wins)
        assertEquals(1, s.losses)
        // Points "for" belong to them, too.
        assertEquals(64 + 79, s.pointsFor)
        assertEquals(70 + 64, s.pointsAgainst)
        assertEquals(71.5, s.pointsForPerGame, 0.01)
    }

    @Test
    fun `unplayed fixtures and other seasons are left out`() {
        val games = listOf(
            game("2025-11-20", "Omaha", 70, 64),
            game("2026-11-03", "Omaha", null, null, season = "2026-27"),
            game("2026-12-01", "Omaha", 80, 60, season = "2026-27"),
        )
        assertEquals(1, summarizeOpponents(games, emptyList(), "2025-26").single().meetings)
        assertEquals(1, summarizeOpponents(games, emptyList(), "2026-27").single().meetings)
        // Every season together still excludes the fixture with no result.
        assertEquals(2, summarizeOpponents(games, emptyList()).single().meetings)
    }

    @Test
    fun `efficiency comes from their official totals, and is null without them`() {
        val games = listOf(game("2025-11-20", "TCU", 70, 64))
        val id = games.single().id
        val stats = listOf(
            teamRow(id, opponent = false, pts = 70, fgm = 26, fga = 60, tpm = 6),
            teamRow(id, opponent = true, pts = 64, fgm = 24, fga = 62, tpm = 8),
        )
        // Only the opponent's own row feeds their efficiency: (24 + 0.5*8)/62.
        val eff = summarizeOpponents(games, stats).single().efficiency!!
        assertEquals(28.0 / 62.0, eff.effectiveFieldGoalPct, 0.0001)
        // A meeting with no team totals recorded says nothing rather than zero.
        assertNull(summarizeOpponents(games, emptyList()).single().efficiency)
    }

    @Test
    fun `an opposing player's line is summed across meetings`() {
        val lines = listOf(
            oppLine(1L, "Kambree Barber", pts = 18, reb = 6, jersey = "14", position = "G", started = true),
            oppLine(2L, "Kambree Barber", pts = 12, reb = 4, jersey = "14", position = "G"),
            oppLine(1L, "Someone Else", pts = 21, reb = 2),
        )
        val players = opponentPlayers(lines)
        // Ordered by points, so the 30-point aggregate leads the 21.
        assertEquals(listOf("Kambree Barber", "Someone Else"), players.map { it.name })
        val barber = players.first()
        assertEquals(2, barber.games)
        assertEquals(1, barber.started)
        assertEquals(30, barber.totals.points)
        assertEquals(10, barber.totals.totalRebounds)
        assertEquals("14", barber.jerseyNumber)
        assertEquals("G", barber.position)
    }

    @Test
    fun `a blank jersey or position on one line does not erase it`() {
        // Box scores are inconsistent about these; the later blank must not win.
        val lines = listOf(
            oppLine(1L, "A Player", pts = 8, jersey = "22", position = "F"),
            oppLine(2L, "A Player", pts = 8, jersey = "", position = ""),
        )
        val p = opponentPlayers(lines).single()
        assertEquals("22", p.jerseyNumber)
        assertEquals("F", p.position)
    }

    @Test
    fun `opponent lines aggregate into the same shape as KU's`() {
        val totals = aggregateOpponentLines(
            listOf(oppLine(1L, "X", pts = 10, reb = 5), oppLine(2L, "X", pts = 6, reb = 3))
        )
        assertEquals(2, totals.games)
        assertEquals(16, totals.points)
        assertEquals(8, totals.totalRebounds)
        assertEquals(8.0, totals.pointsPerGame, 0.01)
    }

    @Test
    fun `an exhibition is not folded into the real head-to-head`() {
        // It counts for nothing, so it must not join a record. The seeder keys
        // games with the same rule, so this also keeps them separate rows.
        assertFalse(sameTeam("Washburn (exh.)", "Washburn"))
        assertTrue(sameTeam("South Dakota St.", "South Dakota State"))
        assertTrue(sameTeam("Baylor (3)", "Baylor"))
        assertEquals("kansas st", normTeam("Kansas State"))
    }
}
