package com.example.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.Game
import com.example.data.Player
import com.example.data.StatLine
import com.example.stats.BasketballTotals
import com.example.stats.aggregate
import com.example.stats.formatPct
import com.example.stats.formatPerGame

private const val ALL_SEASONS = "All"

/** Shooting-percentage leaders must average at least this many attempts per team game. */
private const val MIN_FGA_PER_TEAM_GAME = 3
private const val MIN_FTA_PER_TEAM_GAME = 1

@Composable
fun LeadersScreen(
    players: List<Player>,
    games: List<Game>,
    statLines: List<StatLine>,
    modifier: Modifier = Modifier,
    dataUpdatedAt: String? = null
) {
    // Seasons ordered most recent first; default selection is the current (latest) season.
    val seasons = games.sortedByDescending { it.date }.map { it.season }.distinct()
    var selectedSeason by rememberSaveable { mutableStateOf<String?>(null) }
    val season = selectedSeason ?: seasons.firstOrNull() ?: ALL_SEASONS

    val seasonGames =
        if (season == ALL_SEASONS) games else games.filter { it.season == season }
    val seasonGameIds = seasonGames.map { it.id }.toSet()
    val seasonLines = statLines.filter { it.gameId in seasonGameIds }
    val playersById = players.associateBy { it.id }
    val totalsByPlayer: Map<Long, BasketballTotals> = seasonLines
        .groupBy { it.playerId }
        .mapValues { (_, lines) -> aggregate(lines) }

    val playedGames = seasonGames.filter { it.teamScore != null && it.opponentScore != null }
    val wins = playedGames.count { it.teamScore!! > it.opponentScore!! }
    val losses = playedGames.count { it.teamScore!! < it.opponentScore!! }
    val pointsFor = playedGames.sumOf { it.teamScore ?: 0 }
    val pointsAgainst = playedGames.sumOf { it.opponentScore ?: 0 }
    val teamTotals = aggregate(seasonLines)
    val minFga = playedGames.size * MIN_FGA_PER_TEAM_GAME
    val minFta = playedGames.size * MIN_FTA_PER_TEAM_GAME

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            (seasons + ALL_SEASONS).forEach { s ->
                FilterChip(
                    selected = season == s,
                    onClick = { selectedSeason = s },
                    label = { Text(s) }
                )
            }
        }

        if (seasonGames.isEmpty()) {
            EmptyState(
                title = "No games recorded",
                subtitle = "Add games and stat lines to see team totals and leaderboards here."
            )
            return@Column
        }

        LazyColumn(
            contentPadding = ListContentPadding,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "Jayhawks — $season",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        val ppg = if (playedGames.isEmpty()) 0.0
                        else pointsFor.toDouble() / playedGames.size
                        val oppPpg = if (playedGames.isEmpty()) 0.0
                        else pointsAgainst.toDouble() / playedGames.size
                        Text(
                            "Record ${wins}-${losses}" +
                                " · ${formatPerGame(ppg)} PPG / ${formatPerGame(oppPpg)} allowed",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            "Team ${formatPct(teamTotals.fieldGoalPercentage)} FG · " +
                                "${formatPct(teamTotals.threePointPercentage)} 3PT · " +
                                "${formatPct(teamTotals.freeThrowPercentage)} FT",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        dataUpdatedAt?.let {
                            Text(
                                "Data updated ${it.take(16).replace('T', ' ')} UTC · pull down to refresh",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            val perGameCategories = listOf<Pair<String, (BasketballTotals) -> Double>>(
                "Points Per Game" to { it.pointsPerGame },
                "Rebounds Per Game" to { it.reboundsPerGame },
                "Assists Per Game" to { it.assistsPerGame },
                "Steals Per Game" to { it.stealsPerGame },
                "Blocks Per Game" to { it.blocksPerGame }
            )
            perGameCategories.forEach { (title, selector) ->
                item {
                    LeaderCard(
                        title = title,
                        entries = totalsByPlayer.entries
                            .filter { selector(it.value) > 0 }
                            .sortedByDescending { selector(it.value) }
                            .take(3)
                            .mapNotNull { (playerId, totals) ->
                                playersById[playerId]?.let {
                                    it.name to formatPerGame(selector(totals))
                                }
                            }
                    )
                }
            }

            item {
                LeaderCard(
                    title = "Field Goal %" + if (minFga > 0) " (min $minFga FGA)" else "",
                    entries = totalsByPlayer
                        .filterValues { it.fieldGoalsAttempted >= minFga && it.fieldGoalsAttempted > 0 }
                        .entries
                        .sortedByDescending { it.value.fieldGoalPercentage }
                        .take(3)
                        .mapNotNull { (playerId, totals) ->
                            playersById[playerId]?.let {
                                it.name to formatPct(totals.fieldGoalPercentage)
                            }
                        }
                )
            }

            item {
                LeaderCard(
                    title = "Free Throw %" + if (minFta > 0) " (min $minFta FTA)" else "",
                    entries = totalsByPlayer
                        .filterValues { it.freeThrowsAttempted >= minFta && it.freeThrowsAttempted > 0 }
                        .entries
                        .sortedByDescending { it.value.freeThrowPercentage }
                        .take(3)
                        .mapNotNull { (playerId, totals) ->
                            playersById[playerId]?.let {
                                it.name to formatPct(totals.freeThrowPercentage)
                            }
                        }
                )
            }

            item {
                LeaderCard(
                    title = "Three-Pointers Made",
                    entries = totalsByPlayer.entries
                        .filter { it.value.threePointsMade > 0 }
                        .sortedByDescending { it.value.threePointsMade }
                        .take(3)
                        .mapNotNull { (playerId, totals) ->
                            playersById[playerId]?.let {
                                it.name to totals.threePointsMade.toString()
                            }
                        }
                )
            }
        }
    }
}

@Composable
fun LeaderCard(title: String, entries: List<Pair<String, String>>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            if (entries.isEmpty()) {
                Text(
                    "No qualifying players yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                entries.forEachIndexed { index, (name, value) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${index + 1}.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(
                            name,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            value,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
