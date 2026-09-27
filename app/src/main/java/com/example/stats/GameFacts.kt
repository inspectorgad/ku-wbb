package com.example.stats

import com.example.data.Game
import com.example.data.PollEntry
import com.example.data.StatLine
import kotlin.math.abs

/**
 * The things a fan notices that a table does not say outright: that this was
 * the third win in a row, that the opponent came in ranked and undefeated,
 * that somebody had a double-double.
 *
 * Plain functions over plain data so the claims can be tested. A fact that is
 * not certain is not produced at all — an empty list is a fine answer, and far
 * better than a confident wrong one.
 */

/** Games in calendar order, played only. */
private fun chronological(games: Collection<Game>): List<Game> =
    played(games).sortedBy { it.date }

private fun won(g: Game) = g.teamScore!! > g.opponentScore!!

/**
 * The run of results ending at [upTo] (inclusive), as a signed count:
 * +3 for three straight wins, -2 for two straight losses, 0 for no games.
 */
fun streakAt(games: Collection<Game>, upTo: String): Int {
    val prior = chronological(games).filter { it.date <= upTo }
    if (prior.isEmpty()) return 0
    val latestWon = won(prior.last())
    var run = 0
    for (g in prior.asReversed()) {
        if (won(g) != latestWon) break
        run++
    }
    return if (latestWon) run else -run
}

/** "Third win in a row", "two straight losses", or null for a single game. */
fun streakPhrase(streak: Int): String? = when {
    streak >= 2 -> "$streak straight wins"
    streak <= -2 -> "${-streak} straight losses"
    else -> null
}

/** The facts worth showing beside one game. Empty when there is nothing sure. */
fun gameFacts(
    game: Game,
    allGames: Collection<Game>,
    lines: Collection<StatLine>,
    playerNames: Map<Long, String>
): List<String> {
    val facts = mutableListOf<String>()
    if (game.teamScore == null || game.opponentScore == null) return facts

    // Who the opponent was at the time, which a final score never says.
    val ranked = game.opponentRank?.let { rank ->
        val record = game.opponentRecord?.let { " ($it)" } ?: ""
        "Opponent came in ranked #$rank$record"
    }
    if (ranked != null) facts += ranked
    game.opponentSeed?.let { facts += "The $it seed in the conference tournament" }

    if (won(game) && game.opponentRank != null) {
        facts += "A win over a ranked team"
    }

    game.overtime?.let { facts += if (it > 1) "Went $it overtimes" else "Went to overtime" }

    // The quarter that decided it: the biggest swing either way.
    val periods = parsePeriods(game.periodScores)
    if (periods.size >= 4) {
        val best = periods.withIndex().maxByOrNull { (_, p) -> p.first - p.second }
        val worst = periods.withIndex().minByOrNull { (_, p) -> p.first - p.second }
        if (best != null && best.value.first - best.value.second >= 8) {
            facts += "Outscored them ${best.value.first}-${best.value.second} " +
                "in the ${ordinal(best.index + 1)}"
        }
        if (worst != null && worst.value.second - worst.value.first >= 8) {
            facts += "Outscored ${worst.value.second}-${worst.value.first} " +
                "in the ${ordinal(worst.index + 1)}"
        }
        // Came from behind, which the final score hides.
        val halfUs = periods[0].first + periods[1].first
        val halfThem = periods[0].second + periods[1].second
        if (won(game) && halfThem > halfUs) {
            facts += "Trailed by ${halfThem - halfUs} at the half and won"
        }
    }

    val margin = abs(game.teamScore - game.opponentScore)
    if (margin <= 3) facts += "Decided by $margin"

    val gameLines = lines.filter { it.gameId == game.id }
    for (line in gameLines) {
        val name = playerNames[line.playerId] ?: continue
        if (line.points >= 30) facts += "$name scored ${line.points}"
        if (line.points >= 10 && line.totalRebounds >= 10) {
            facts += "$name: ${line.points} and ${line.totalRebounds}, a double-double"
        } else if (line.points >= 10 && line.assists >= 10) {
            facts += "$name: ${line.points} points and ${line.assists} assists"
        }
    }

    streakPhrase(streakAt(allGames, game.date))?.let { facts += it }
    return facts
}

private fun ordinal(n: Int) = when (n) {
    1 -> "first quarter"; 2 -> "second quarter"; 3 -> "third quarter"; 4 -> "fourth quarter"
    else -> "overtime"
}

/** A season high, with who or what set it. */
data class Highlight(val label: String, val value: String, val detail: String)

/** The season's notable marks. Only computed from games that were played. */
fun seasonHighlights(
    games: Collection<Game>,
    lines: Collection<StatLine>,
    playerNames: Map<Long, String>
): List<Highlight> {
    val finished = chronological(games)
    if (finished.isEmpty()) return emptyList()
    val byId = finished.associateBy { it.id }
    val out = mutableListOf<Highlight>()

    finished.maxByOrNull { it.teamScore!! }?.let {
        out += Highlight("Most points", "${it.teamScore}", "vs ${it.opponent}")
    }
    finished.maxByOrNull { it.teamScore!! - it.opponentScore!! }?.let {
        val m = it.teamScore!! - it.opponentScore!!
        if (m > 0) out += Highlight("Biggest win", "+$m", "vs ${it.opponent}")
    }
    val relevant = lines.filter { it.gameId in byId.keys }
    relevant.maxByOrNull { it.points }?.let { line ->
        val name = playerNames[line.playerId]
        if (name != null && line.points > 0) {
            out += Highlight(
                "Most points, player", "${line.points}",
                "$name vs ${byId[line.gameId]?.opponent ?: "?"}"
            )
        }
    }
    relevant.maxByOrNull { it.totalRebounds }?.let { line ->
        val name = playerNames[line.playerId]
        if (name != null && line.totalRebounds > 0) {
            out += Highlight(
                "Most rebounds", "${line.totalRebounds}",
                "$name vs ${byId[line.gameId]?.opponent ?: "?"}"
            )
        }
    }
    val doubleDoubles = relevant.count { it.points >= 10 && it.totalRebounds >= 10 }
    if (doubleDoubles > 0) {
        out += Highlight("Double-doubles", "$doubleDoubles", "points and rebounds")
    }
    val best = streakOfWins(finished)
    if (best >= 2) out += Highlight("Longest win streak", "$best", "games")
    return out
}

private fun streakOfWins(chronological: List<Game>): Int {
    var best = 0
    var run = 0
    for (g in chronological) {
        run = if (won(g)) run + 1 else 0
        if (run > best) best = run
    }
    return best
}

/**
 * How a team moved in the poll since the last one, as a signed places count.
 * Null when it was unranked before or the previous position is unreadable —
 * "NR" and a blank are both common and neither is a number.
 */
fun pollMovement(entry: PollEntry): Int? {
    val previous = entry.previous.trim().toIntOrNull() ?: return null
    if (entry.rank <= 0) return null
    // Moving from 12th to 6th is a rise of six.
    return previous - entry.rank
}
