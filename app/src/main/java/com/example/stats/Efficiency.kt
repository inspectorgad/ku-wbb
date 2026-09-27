package com.example.stats

import com.example.data.GameTeamStats

/**
 * Shooting efficiency — the figures raw percentages miss.
 *
 * Field goal percentage counts a three and a layup the same, and ignores free
 * throws entirely, so a team that lives at the line or behind the arc looks
 * worse than it scores. These are the standard corrections.
 *
 * Deliberately plain functions over plain numbers rather than methods on an
 * entity: the same arithmetic has to serve a player's season, the team's
 * official totals and the opponent's, which are three different row types.
 */
data class Efficiency(
    val fieldGoalsMade: Int,
    val fieldGoalsAttempted: Int,
    val threePointsMade: Int,
    val threePointsAttempted: Int,
    val freeThrowsMade: Int,
    val freeThrowsAttempted: Int,
    val points: Int,
    val assists: Int,
    val turnovers: Int
) {
    /**
     * Effective field goal percentage: a three counts for the extra half a
     * basket it is worth. (FGM + 0.5 · 3PM) / FGA.
     */
    val effectiveFieldGoalPct: Double
        get() = if (fieldGoalsAttempted == 0) 0.0
        else (fieldGoalsMade + 0.5 * threePointsMade) / fieldGoalsAttempted

    /**
     * True shooting percentage: points per scoring attempt, counting free
     * throws. The 0.44 is the standard estimate of how many trips to the line
     * a free-throw attempt represents, since and-ones and three-shot fouls
     * mean attempts and trips are not the same thing.
     */
    val trueShootingPct: Double
        get() {
            val attempts = fieldGoalsAttempted + 0.44 * freeThrowsAttempted
            return if (attempts == 0.0) 0.0 else points / (2 * attempts)
        }

    /** Three-point attempt rate: how much of the shot diet is from deep. */
    val threePointAttemptRate: Double
        get() = if (fieldGoalsAttempted == 0) 0.0
        else threePointsAttempted.toDouble() / fieldGoalsAttempted

    /** Free-throw rate: how often shooting turns into trips to the line. */
    val freeThrowRate: Double
        get() = if (fieldGoalsAttempted == 0) 0.0
        else freeThrowsAttempted.toDouble() / fieldGoalsAttempted

    /** Assists per turnover. Null rather than infinity when nothing was lost. */
    val assistToTurnover: Double?
        get() = if (turnovers == 0) null else assists.toDouble() / turnovers
}

/** Efficiency for any set of player lines — one game, a season, a career. */
fun BasketballTotals.efficiency(): Efficiency = Efficiency(
    fieldGoalsMade = fieldGoalsMade,
    fieldGoalsAttempted = fieldGoalsAttempted,
    threePointsMade = threePointsMade,
    threePointsAttempted = threePointsAttempted,
    freeThrowsMade = freeThrowsMade,
    freeThrowsAttempted = freeThrowsAttempted,
    points = points,
    assists = assists,
    turnovers = turnovers
)

/**
 * Efficiency from the official team totals, which is what a team-level figure
 * should use — summing player lines understates team rebounds and turnovers.
 */
fun Collection<GameTeamStats>.efficiency(): Efficiency = Efficiency(
    fieldGoalsMade = sumOf { it.fgm },
    fieldGoalsAttempted = sumOf { it.fga },
    threePointsMade = sumOf { it.tpm },
    threePointsAttempted = sumOf { it.tpa },
    freeThrowsMade = sumOf { it.ftm },
    freeThrowsAttempted = sumOf { it.fta },
    points = sumOf { it.pts },
    assists = sumOf { it.ast },
    turnovers = sumOf { it.to }
)

/**
 * The season's efficiency after each game, in order — the running figure, not
 * each game on its own. A single cold night barely moves a season average, and
 * that is the point: it shows where the season actually settled.
 */
fun cumulativeEfficiency(perGame: List<GameTeamStats>): List<Efficiency> {
    val running = mutableListOf<GameTeamStats>()
    return perGame.map { game ->
        running += game
        running.efficiency()
    }
}
