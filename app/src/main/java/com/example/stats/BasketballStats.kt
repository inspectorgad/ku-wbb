package com.example.stats

import com.example.data.StatLine
import java.util.Locale

/**
 * Aggregated basketball totals for any collection of stat lines
 * (one player's game, a season, a career, or the whole team).
 */
data class BasketballTotals(
    val games: Int = 0,
    val gamesStarted: Int = 0,
    val minutes: Int = 0,
    val fieldGoalsMade: Int = 0,
    val fieldGoalsAttempted: Int = 0,
    val threePointsMade: Int = 0,
    val threePointsAttempted: Int = 0,
    val freeThrowsMade: Int = 0,
    val freeThrowsAttempted: Int = 0,
    val offensiveRebounds: Int = 0,
    val totalRebounds: Int = 0,
    val assists: Int = 0,
    val turnovers: Int = 0,
    val steals: Int = 0,
    val blocks: Int = 0,
    val personalFouls: Int = 0,
    val points: Int = 0
) {
    val fieldGoalPercentage: Double
        get() = pct(fieldGoalsMade, fieldGoalsAttempted)
    val threePointPercentage: Double
        get() = pct(threePointsMade, threePointsAttempted)
    val freeThrowPercentage: Double
        get() = pct(freeThrowsMade, freeThrowsAttempted)

    val pointsPerGame: Double get() = perGame(points)
    val reboundsPerGame: Double get() = perGame(totalRebounds)
    val assistsPerGame: Double get() = perGame(assists)
    val stealsPerGame: Double get() = perGame(steals)
    val blocksPerGame: Double get() = perGame(blocks)
    val minutesPerGame: Double get() = perGame(minutes)

    private fun pct(made: Int, attempted: Int): Double =
        if (attempted == 0) 0.0 else made.toDouble() / attempted

    private fun perGame(value: Int): Double =
        if (games == 0) 0.0 else value.toDouble() / games
}

/** Sums a set of stat lines into one totals row. */
fun aggregate(lines: Collection<StatLine>): BasketballTotals = BasketballTotals(
    games = lines.size,
    gamesStarted = lines.count { it.started },
    minutes = lines.sumOf { it.minutes },
    fieldGoalsMade = lines.sumOf { it.fieldGoalsMade },
    fieldGoalsAttempted = lines.sumOf { it.fieldGoalsAttempted },
    threePointsMade = lines.sumOf { it.threePointsMade },
    threePointsAttempted = lines.sumOf { it.threePointsAttempted },
    freeThrowsMade = lines.sumOf { it.freeThrowsMade },
    freeThrowsAttempted = lines.sumOf { it.freeThrowsAttempted },
    offensiveRebounds = lines.sumOf { it.offensiveRebounds },
    totalRebounds = lines.sumOf { it.totalRebounds },
    assists = lines.sumOf { it.assists },
    turnovers = lines.sumOf { it.turnovers },
    steals = lines.sumOf { it.steals },
    blocks = lines.sumOf { it.blocks },
    personalFouls = lines.sumOf { it.personalFouls },
    points = lines.sumOf { it.points }
)

/** Formats a shooting percentage basketball-style: .452, .000, 1.000 */
fun formatPct(value: Double): String {
    val formatted = String.format(Locale.US, "%.3f", value)
    return formatted
        .replace("0.", ".")
        .let { if (it == "-.000") ".000" else it }
}

/** Formats a per-game rate: 17.4, 0.4 */
fun formatPerGame(value: Double): String = String.format(Locale.US, "%.1f", value)

/** "12-25" made-attempted pair, the usual box-score shot notation. */
fun formatShots(made: Int, attempted: Int): String = "$made-$attempted"

/** Short human summary of a single game line, e.g. "21 PTS, 9 REB, 4 AST, 2 BLK". */
fun summarize(line: StatLine): String {
    val parts = mutableListOf<String>()
    if (line.points > 0 || line.fieldGoalsAttempted > 0) parts.add("${line.points} PTS")
    if (line.totalRebounds > 0) parts.add("${line.totalRebounds} REB")
    if (line.assists > 0) parts.add("${line.assists} AST")
    if (line.steals > 0) parts.add("${line.steals} STL")
    if (line.blocks > 0) parts.add("${line.blocks} BLK")
    if (parts.isEmpty()) {
        return if (line.minutes > 0) "${line.minutes} min played" else "No stats"
    }
    return parts.joinToString(", ")
}
