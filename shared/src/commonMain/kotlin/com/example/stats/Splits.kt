package com.example.stats

import com.example.data.Game

/**
 * Season splits and quarter-by-quarter shape.
 *
 * Plain functions over plain data, deliberately outside the composables, so
 * the arithmetic can be tested without a screen — the answers here are claims
 * about the season ("a fourth-quarter team") that ought to be checkable.
 */

/** A won-lost record, and the scoring behind it. */
data class Record(
    val wins: Int = 0,
    val losses: Int = 0,
    val pointsFor: Int = 0,
    val pointsAgainst: Int = 0
) {
    val games: Int get() = wins + losses
    val winPct: Double get() = if (games == 0) 0.0 else wins.toDouble() / games
    val pointsForPerGame: Double get() = if (games == 0) 0.0 else pointsFor.toDouble() / games
    val pointsAgainstPerGame: Double get() = if (games == 0) 0.0 else pointsAgainst.toDouble() / games
    val margin: Double get() = pointsForPerGame - pointsAgainstPerGame
    override fun toString(): String = "$wins-$losses"
}

/** Only games with a final score; a fixture has no record to contribute. */
fun played(games: Collection<Game>): List<Game> =
    games.filter { it.teamScore != null && it.opponentScore != null }

fun recordOf(games: Collection<Game>): Record {
    var r = Record()
    for (g in played(games)) {
        val us = g.teamScore!!
        val them = g.opponentScore!!
        r = Record(
            wins = r.wins + if (us > them) 1 else 0,
            losses = r.losses + if (us > them) 0 else 1,
            pointsFor = r.pointsFor + us,
            pointsAgainst = r.pointsAgainst + them
        )
    }
    return r
}

/** A named slice of the season, e.g. "Home" -> 14-4. */
data class Split(val label: String, val record: Record)

/**
 * Home, away and neutral. Reported separately rather than folded into
 * home/away because a neutral floor is neither, and the NCAA's own splits
 * treat it as its own line.
 */
fun siteSplits(games: Collection<Game>): List<Split> =
    listOf("home" to "Home", "away" to "Road", "neutral" to "Neutral").mapNotNull { (key, label) ->
        val slice = played(games).filter { it.siteOrLegacy == key }
        if (slice.isEmpty()) null else Split(label, recordOf(slice))
    }

/**
 * Big 12 against everything else. The conference flag is the scraper's, which
 * counts only regular-season games against members — the conference tournament
 * is not part of the standings record.
 */
fun conferenceSplits(games: Collection<Game>): List<Split> {
    val all = played(games)
    val conference = all.filter { it.conference }
    val other = all.filterNot { it.conference }
    return buildList {
        if (conference.isNotEmpty()) add(Split("Big 12", recordOf(conference)))
        if (other.isNotEmpty()) add(Split("Non-conference", recordOf(other)))
    }
}

/** Blowouts, comfortable wins and coin flips, by final margin. */
fun marginSplits(games: Collection<Game>): List<Split> {
    val all = played(games)
    fun slice(label: String, test: (Int) -> Boolean): Split? {
        val s = all.filter { test(kotlin.math.abs(it.teamScore!! - it.opponentScore!!)) }
        return if (s.isEmpty()) null else Split(label, recordOf(s))
    }
    return listOfNotNull(
        slice("Within 5") { it <= 5 },
        slice("By 6-15") { it in 6..15 },
        slice("By 16+") { it >= 16 },
    )
}

// ---------------------------------------------------------------------------
// Quarters
// ---------------------------------------------------------------------------

/** One quarter's scoring across a set of games. */
data class QuarterSplit(
    val label: String,
    val pointsFor: Int = 0,
    val pointsAgainst: Int = 0,
    val won: Int = 0,
    val lost: Int = 0,
    val tied: Int = 0
) {
    val games: Int get() = won + lost + tied
    val margin: Int get() = pointsFor - pointsAgainst
    val forPerGame: Double get() = if (games == 0) 0.0 else pointsFor.toDouble() / games
    val againstPerGame: Double get() = if (games == 0) 0.0 else pointsAgainst.toDouble() / games
}

/**
 * Splits "8-11, 26-17, 17-16, 23-20" into ours/theirs pairs.
 *
 * Returns an empty list rather than a partial one if any period is malformed:
 * a quarter table built from half a game is worse than no quarter table.
 */
fun parsePeriods(periodScores: String?): List<Pair<Int, Int>> {
    val text = periodScores?.trim().orEmpty()
    if (text.isEmpty()) return emptyList()
    val periods = text.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    val parsed = periods.map { period ->
        val parts = period.split("-").map { it.trim() }
        if (parts.size != 2) return emptyList()
        val ours = parts[0].toIntOrNull() ?: return emptyList()
        val theirs = parts[1].toIntOrNull() ?: return emptyList()
        ours to theirs
    }
    return parsed
}

/** Regulation quarter labels, then OT periods as the data supplies them. */
private fun quarterLabel(index: Int, total: Int): String = when {
    index < 4 -> "Q${index + 1}"
    total == 5 -> "OT"
    else -> "${index - 3}OT"
}

/**
 * Scoring by quarter across a season. Overtime periods are kept separate from
 * regulation — pooling them would flatter the fourth quarter of games that
 * needed five.
 */
fun quarterSplits(games: Collection<Game>): List<QuarterSplit> {
    val byIndex = sortedMapOf<Int, QuarterSplit>()
    for (g in played(games)) {
        val periods = parsePeriods(g.periodScores)
        periods.forEachIndexed { i, (ours, theirs) ->
            val current = byIndex[i] ?: QuarterSplit(quarterLabel(i, periods.size))
            byIndex[i] = current.copy(
                // A game that goes to five periods labels its fifth "OT"; one
                // that goes to six labels the same slot "2OT". The first
                // sighting names it, which is the common case either way.
                label = current.label,
                pointsFor = current.pointsFor + ours,
                pointsAgainst = current.pointsAgainst + theirs,
                won = current.won + if (ours > theirs) 1 else 0,
                lost = current.lost + if (ours < theirs) 1 else 0,
                tied = current.tied + if (ours == theirs) 1 else 0
            )
        }
    }
    return byIndex.values.toList()
}

/**
 * The scoring margin accumulated through each quarter — where a season's games
 * are actually decided, rather than how they finish.
 */
fun cumulativeQuarterMargin(games: Collection<Game>): List<Pair<String, Int>> {
    var running = 0
    return quarterSplits(games).map { q ->
        running += q.margin
        q.label to running
    }
}

/** How the team fares when it leads, trails or is level at the half. */
fun halftimeSplits(games: Collection<Game>): List<Split> {
    val leading = mutableListOf<Game>()
    val trailing = mutableListOf<Game>()
    val level = mutableListOf<Game>()
    for (g in played(games)) {
        val periods = parsePeriods(g.periodScores)
        if (periods.size < 2) continue
        val ours = periods[0].first + periods[1].first
        val theirs = periods[0].second + periods[1].second
        when {
            ours > theirs -> leading += g
            ours < theirs -> trailing += g
            else -> level += g
        }
    }
    return listOfNotNull(
        leading.takeIf { it.isNotEmpty() }?.let { Split("Leading at half", recordOf(it)) },
        trailing.takeIf { it.isNotEmpty() }?.let { Split("Trailing at half", recordOf(it)) },
        level.takeIf { it.isNotEmpty() }?.let { Split("Level at half", recordOf(it)) },
    )
}
