package com.example.stats

import com.example.data.Game
import com.example.data.GameTeamStats
import com.example.data.OpponentStatLine
import com.example.data.normTeam

/**
 * What one opponent did against Kansas.
 *
 * Everything here is scoped to meetings with KU, because meetings with KU are
 * the only box scores this app holds. [wins] and [losses] are stated from the
 * opponent's side — a row reads as their record against us — and the screen
 * says so rather than letting it be mistaken for their season.
 */
data class OpponentSummary(
    val name: String,
    val meetings: Int,
    val wins: Int,
    val losses: Int,
    val pointsFor: Int,
    val pointsAgainst: Int,
    /** Their official team totals across the meetings, or null if none were recorded. */
    val totals: List<GameTeamStats>
) {
    val pointsForPerGame: Double get() = if (meetings == 0) 0.0 else pointsFor.toDouble() / meetings
    val pointsAgainstPerGame: Double get() =
        if (meetings == 0) 0.0 else pointsAgainst.toDouble() / meetings
    val efficiency: Efficiency? get() = totals.takeIf { it.isNotEmpty() }?.efficiency()
}

/**
 * One row per opponent, over the played games of [season] (or every season
 * when it is null).
 *
 * Grouped on the normalized name: the box scores say "South Dakota St." where
 * the schedule says "South Dakota State", and grouping on the literal name
 * splits one opponent's history in two. The row is titled with the most recent
 * spelling.
 */
fun summarizeOpponents(
    games: List<Game>,
    teamStats: List<GameTeamStats>,
    season: String? = null
): List<OpponentSummary> {
    val played = games.filter {
        it.teamScore != null && it.opponentScore != null && (season == null || it.season == season)
    }
    val statsByGame = teamStats.groupBy { it.gameId }

    return played.groupBy { normTeam(it.opponent) }.map { (_, theirGames) ->
        OpponentSummary(
            name = theirGames.maxBy { it.date }.opponent,
            meetings = theirGames.size,
            wins = theirGames.count { (it.opponentScore ?: 0) > (it.teamScore ?: 0) },
            losses = theirGames.count { (it.opponentScore ?: 0) < (it.teamScore ?: 0) },
            pointsFor = theirGames.sumOf { it.opponentScore ?: 0 },
            pointsAgainst = theirGames.sumOf { it.teamScore ?: 0 },
            totals = theirGames.flatMap { g -> statsByGame[g.id].orEmpty().filter { it.opponent } }
        )
    }.sortedBy { it.name }
}

/**
 * One opposing player's line summed across every meeting with Kansas.
 *
 * Keyed on the player's name, which is all an opposing box score gives — there
 * is no roster behind these, so two players with the same name on different
 * teams would merge. That cannot happen here: the caller has already narrowed
 * to one opponent.
 */
data class OpponentPlayer(
    val name: String,
    val jerseyNumber: String,
    val position: String,
    /** From her school's roster page; empty when that roster was unreachable. */
    val height: String,
    val games: Int,
    val started: Int,
    val totals: BasketballTotals
)

/** Sums opposing box score lines into the same totals shape KU's use. */
fun aggregateOpponentLines(lines: Collection<OpponentStatLine>): BasketballTotals =
    BasketballTotals(
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
        totalRebounds = lines.sumOf { it.rebounds },
        assists = lines.sumOf { it.assists },
        turnovers = lines.sumOf { it.turnovers },
        steals = lines.sumOf { it.steals },
        blocks = lines.sumOf { it.blocks },
        personalFouls = lines.sumOf { it.fouls },
        points = lines.sumOf { it.points }
    )

/** The opposing players who appeared against Kansas, most points first. */
fun opponentPlayers(lines: Collection<OpponentStatLine>): List<OpponentPlayer> =
    lines.groupBy { it.playerName }.map { (name, rows) ->
        // Jersey and position come from the most recent line that carries
        // them: a player can change number between seasons, and an occasional
        // box score leaves the position blank.
        val latest = rows.sortedBy { it.gameId }
        OpponentPlayer(
            name = name,
            jerseyNumber = latest.lastOrNull { it.jerseyNumber.isNotBlank() }?.jerseyNumber.orEmpty(),
            position = latest.lastOrNull { it.position.isNotBlank() }?.position.orEmpty(),
            // Same rule as the two above: a roster scrape that failed on one
            // game must not blank a height another game supplied.
            height = latest.lastOrNull { it.height.isNotBlank() }?.height.orEmpty(),
            games = rows.size,
            started = rows.count { it.started },
            totals = aggregateOpponentLines(rows)
        )
    }.sortedWith(compareByDescending<OpponentPlayer> { it.totals.points }.thenBy { it.name })
