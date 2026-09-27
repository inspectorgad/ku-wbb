package com.example.stats

import com.example.data.Game
import com.example.data.StatLine

/**
 * A player's recent form against her own season — whether she is playing
 * better than usual lately, and by how much.
 *
 * Deliberately conservative: a comparison drawn from two games is noise, so
 * one is not offered at all.
 */

/** The fewest recent games worth calling "form" rather than a coincidence. */
const val FORM_WINDOW = 5

data class Form(
    val recent: BasketballTotals,
    val season: BasketballTotals,
    val window: Int
) {
    val pointsDelta: Double get() = recent.pointsPerGame - season.pointsPerGame
    val reboundsDelta: Double get() = recent.reboundsPerGame - season.reboundsPerGame
    val assistsDelta: Double get() = recent.assistsPerGame - season.assistsPerGame

    /** Whether the recent run is meaningfully above or below her season. */
    val trend: String
        get() = when {
            pointsDelta >= 2.0 -> "up"
            pointsDelta <= -2.0 -> "down"
            else -> "steady"
        }
}

/**
 * The player's last [FORM_WINDOW] games against her season, or null when she
 * has not played enough for the comparison to mean anything.
 */
fun playerForm(
    lines: Collection<StatLine>,
    gamesById: Map<Long, Game>,
    window: Int = FORM_WINDOW
): Form? {
    val dated = lines
        .mapNotNull { line -> gamesById[line.gameId]?.let { it.date to line } }
        .sortedBy { it.first }
        .map { it.second }
    // Needs the window itself plus enough behind it to compare against.
    if (dated.size < window + 2) return null
    val recent = dated.takeLast(window)
    return Form(aggregate(recent), aggregate(dated), window)
}

/** A player's best single games by a chosen measure. */
fun bestGames(
    lines: Collection<StatLine>,
    gamesById: Map<Long, Game>,
    count: Int = 3,
    by: (StatLine) -> Int = { it.points }
): List<Pair<Game, StatLine>> = lines
    .mapNotNull { line -> gamesById[line.gameId]?.let { it to line } }
    .filter { by(it.second) > 0 }
    .sortedByDescending { by(it.second) }
    .take(count)

/**
 * Milestones a player reached this season. Only whole, checkable facts — a
 * threshold crossed, a count of double-doubles — never an extrapolation.
 */
fun playerMilestones(totals: BasketballTotals, lines: Collection<StatLine>): List<String> {
    val out = mutableListOf<String>()
    val thresholds = listOf(500, 400, 300, 200, 100)
    thresholds.firstOrNull { totals.points >= it }?.let { out += "${totals.points} points" }
    if (totals.games > 0 && totals.pointsPerGame >= 15.0) {
        out += "${formatPerGame(totals.pointsPerGame)} per game"
    }
    val doubleDoubles = lines.count { it.points >= 10 && it.totalRebounds >= 10 }
    if (doubleDoubles > 0) {
        out += "$doubleDoubles double-double${if (doubleDoubles == 1) "" else "s"}"
    }
    if (totals.games > 0 && totals.gamesStarted == totals.games && totals.games >= 10) {
        out += "Started every game"
    }
    if (totals.threePointsMade >= 50) out += "${totals.threePointsMade} threes"
    return out
}

/** A player's record when she plays, split by where the game was. */
fun playerSiteSplits(
    lines: Collection<StatLine>,
    gamesById: Map<Long, Game>
): List<Pair<String, BasketballTotals>> =
    listOf("home" to "Home", "away" to "Road", "neutral" to "Neutral").mapNotNull { (key, label) ->
        val slice = lines.filter { gamesById[it.gameId]?.siteOrLegacy == key }
        if (slice.isEmpty()) null else label to aggregate(slice)
    }
