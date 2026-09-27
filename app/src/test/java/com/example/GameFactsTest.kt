package com.example

import com.example.data.Game
import com.example.data.PollEntry
import com.example.data.StatLine
import com.example.stats.gameFacts
import com.example.stats.playerForm
import com.example.stats.playerMilestones
import com.example.stats.pollMovement
import com.example.stats.seasonHighlights
import com.example.stats.streakAt
import com.example.stats.streakPhrase
import com.example.stats.aggregate
import com.example.stats.bestGames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Facts are assertions in the app's own voice, so a wrong one is worse than a
 * missing one. These check that each is produced only when it is true, and
 * that the uncertain cases produce nothing at all.
 */
class GameFactsTest {

    private var nextId = 1L

    private fun game(
        date: String, opponent: String, us: Int?, them: Int?,
        periods: String? = null, rank: Int? = null, record: String? = null,
        overtime: Int? = null, site: String = "home"
    ) = Game(
        id = nextId++, date = date, opponent = opponent, season = "2025-26",
        site = site, teamScore = us, opponentScore = them, periodScores = periods,
        opponentRank = rank, opponentRecord = record, overtime = overtime
    )

    private fun line(gameId: Long, playerId: Long, pts: Int, reb: Int = 0, ast: Int = 0) =
        StatLine(gameId = gameId, playerId = playerId, points = pts, totalRebounds = reb, assists = ast)

    @Test
    fun `a streak counts back only while the result stays the same`() {
        val games = listOf(
            game("2025-11-05", "A", 74, 64),
            game("2025-11-09", "B", 75, 60),
            game("2025-11-12", "C", 65, 54),
            game("2025-11-15", "D", 62, 68),
        )
        assertEquals(3, streakAt(games, "2025-11-12"))
        assertEquals(-1, streakAt(games, "2025-11-15"))
        assertEquals(1, streakAt(games, "2025-11-05"))
        // Nothing has been played yet at that date.
        assertEquals(0, streakAt(games, "2025-10-01"))
    }

    @Test
    fun `a streak is only worth saying once it is a run`() {
        assertEquals("3 straight wins", streakPhrase(3))
        assertEquals("2 straight losses", streakPhrase(-2))
        // One game is not a streak, and saying so would be padding.
        assertNull(streakPhrase(1))
        assertNull(streakPhrase(-1))
        assertNull(streakPhrase(0))
    }

    @Test
    fun `a ranked opponent and the win over it are both reported`() {
        val g = game("2026-02-25", "Texas Tech", 68, 59, rank = 20, record = "24-5")
        val facts = gameFacts(g, listOf(g), emptyList(), emptyMap())
        assertTrue(facts.any { it.contains("ranked #20") && it.contains("24-5") })
        assertTrue(facts.any { it.contains("win over a ranked team") })
    }

    @Test
    fun `a loss to a ranked opponent does not claim a win over one`() {
        val g = game("2026-01-11", "Baylor", 64, 79, rank = 16, record = "14-3")
        val facts = gameFacts(g, listOf(g), emptyList(), emptyMap())
        assertTrue(facts.any { it.contains("ranked #16") })
        assertTrue(facts.none { it.contains("win over") })
    }

    @Test
    fun `coming from behind is reported, and only when it happened`() {
        // Trailed 6-16 after one, 24-18 the next: down 10 at the half, won by 12.
        val comeback = game("2025-12-03", "Northwestern", 74, 62, periods = "6-16, 24-18, 26-13, 18-15")
        val facts = gameFacts(comeback, listOf(comeback), emptyList(), emptyMap())
        assertTrue(facts.any { it.contains("Trailed by 4 at the half and won") })

        // Led the whole way: no comeback claim.
        val wire = game("2025-11-09", "B", 75, 60, periods = "22-12, 17-11, 17-21, 19-16")
        assertTrue(
            gameFacts(wire, listOf(wire), emptyList(), emptyMap())
                .none { it.contains("Trailed") }
        )
    }

    @Test
    fun `overtime and tight finishes are noted`() {
        val ot = game("2025-12-07", "Missouri St.", 73, 70, overtime = 1)
        val facts = gameFacts(ot, listOf(ot), emptyList(), emptyMap())
        assertTrue(facts.any { it == "Went to overtime" })
        assertTrue(facts.any { it == "Decided by 3" })
    }

    @Test
    fun `big individual games are named`() {
        val g = game("2025-11-28", "Georgia", 62, 68)
        val facts = gameFacts(
            g, listOf(g),
            listOf(line(g.id, 1L, pts = 40), line(g.id, 2L, pts = 7, reb = 12)),
            mapOf(1L to "S'Mya Nichols", 2L to "Lilly Meister")
        )
        assertTrue(facts.any { it == "S'Mya Nichols scored 40" })
        // Seven points and twelve rebounds is not a double-double.
        assertTrue(facts.none { it.contains("double-double") })
    }

    @Test
    fun `a fixture with no result produces no facts at all`() {
        val fixture = game("2026-11-03", "Omaha", null, null)
        assertTrue(gameFacts(fixture, listOf(fixture), emptyList(), emptyMap()).isEmpty())
    }

    @Test
    fun `season highlights come from real games only`() {
        val games = listOf(
            game("2025-12-17", "Haskell", 107, 39),
            game("2025-11-05", "A", 74, 64),
            game("2026-11-03", "Omaha", null, null),
        )
        val lines = listOf(line(games[0].id, 1L, pts = 26, reb = 4))
        val highs = seasonHighlights(games, lines, mapOf(1L to "Brittany Harshaw"))
            .associate { it.label to it.value }
        assertEquals("107", highs.getValue("Most points"))
        assertEquals("+68", highs.getValue("Biggest win"))
        assertEquals("26", highs.getValue("Most points, player"))
    }

    @Test
    fun `poll movement needs a readable previous position`() {
        fun entry(rank: Int, previous: String) =
            PollEntry(season = "2025-26", team = "TCU", rank = rank, rankLabel = "$rank",
                previous = previous)
        assertEquals(6, pollMovement(entry(6, "12")))    // climbed six
        assertEquals(-3, pollMovement(entry(15, "12")))  // fell three
        // "NR" and a blank are both common, and neither is a number.
        assertNull(pollMovement(entry(22, "NR")))
        assertNull(pollMovement(entry(22, "")))
    }

    @Test
    fun `player form needs enough games to mean anything`() {
        val games = (1..8).map { game("2025-11-0$it", "Opp$it", 70, 60) }
        val byId = games.associateBy { it.id }
        // Three games is not form.
        assertNull(playerForm(games.take(3).map { line(it.id, 1L, pts = 20) }, byId))
        // Eight is: the last five against all eight.
        val form = playerForm(
            games.mapIndexed { i, g -> line(g.id, 1L, pts = if (i < 3) 5 else 20) }, byId
        )!!
        assertEquals(5, form.window)
        assertEquals(20.0, form.recent.pointsPerGame, 0.01)
        assertEquals("up", form.trend)
        assertTrue(form.pointsDelta > 0)
    }

    @Test
    fun `milestones state what happened, not what might`() {
        val lines = (1..12).map { line(it.toLong(), 1L, pts = 20, reb = 11) }
        val milestones = playerMilestones(aggregate(lines), lines)
        assertTrue(milestones.any { it == "240 points" })
        assertTrue(milestones.any { it == "12 double-doubles" })
        // Nothing here projects a season total or a record pace.
        assertTrue(milestones.none { it.contains("on pace") || it.contains("projected") })
    }

    @Test
    fun `best games are ordered and exclude blanks`() {
        val games = (1..4).map { game("2025-11-0$it", "Opp$it", 70, 60) }
        val byId = games.associateBy { it.id }
        val lines = listOf(
            line(games[0].id, 1L, pts = 12),
            line(games[1].id, 1L, pts = 0),
            line(games[2].id, 1L, pts = 31),
            line(games[3].id, 1L, pts = 18),
        )
        val best = bestGames(lines, byId, count = 3)
        assertEquals(listOf(31, 18, 12), best.map { it.second.points })
    }
}
