package com.example.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.Game
import com.example.data.GameTeamStats
import com.example.data.OpponentStatLine
import com.example.data.sameTeam
import com.example.stats.efficiency
import com.example.stats.formatPct
import com.example.stats.formatPerGame
import com.example.stats.formatShots
import com.example.stats.opponentPlayers
import com.example.stats.summarizeOpponents

/**
 * Who Kansas played, and what they did in the meeting.
 *
 * Everything on this tab is scoped to games against Kansas — those are the only
 * box scores the app holds — and every figure is stated from the opponent's
 * side, so a row reads as their record against us. The caption says so
 * outright, because "Baylor 1-1" could otherwise be mistaken for their season.
 */
@Composable
fun OpponentsScreen(
    games: List<Game>,
    teamStats: List<GameTeamStats>,
    onOpenOpponent: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val seasons = games.filter { it.teamScore != null }
        .map { it.season }.distinct().sortedDescending()
    var selectedSeason by rememberSaveable { mutableStateOf<String?>(null) }
    val season = selectedSeason?.takeIf { it in seasons } ?: seasons.firstOrNull()

    val summaries = remember(games, teamStats, season) {
        summarizeOpponents(games, teamStats, season)
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (summaries.isEmpty()) {
            EmptyState(
                title = "No opponents yet",
                subtitle = "Each team Kansas has played appears here once a game has been played.",
                modifier = Modifier.align(Alignment.Center)
            )
            return@Box
        }
        LazyColumn(
            contentPadding = ListContentPadding,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (seasons.size > 1) {
                item(key = "seasons") {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        seasons.forEach { s ->
                            FilterChip(
                                selected = s == season,
                                onClick = { selectedSeason = s },
                                label = { Text(s) }
                            )
                        }
                    }
                }
            }
            item(key = "caption") {
                Text(
                    "Records and figures below are against Kansas only, not each opponent's " +
                        "own season, and are stated from their side.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            items(summaries, key = { it.name }) { opponent ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenOpponent(opponent.name) }
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                opponent.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "${opponent.wins}-${opponent.losses} vs KU · ${opponent.meetings} " +
                                    if (opponent.meetings == 1) "meeting" else "meetings",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                formatPerGame(opponent.pointsForPerGame),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "scored · " + (opponent.efficiency
                                    ?.let { "${formatPct(it.effectiveFieldGoalPct)} eFG" }
                                    ?: "no box score"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpponentDetailScreen(
    opponentName: String,
    games: List<Game>,
    opponentLines: List<OpponentStatLine>,
    teamStats: List<GameTeamStats>,
    onBack: () -> Unit
) {
    val theirGames = games
        .filter { sameTeam(it.opponent, opponentName) && it.teamScore != null }
        .sortedByDescending { it.date }
    val ids = theirGames.map { it.id }.toSet()
    val players = remember(opponentLines, ids) {
        opponentPlayers(opponentLines.filter { it.gameId in ids })
    }
    val stats = teamStats.filter { it.gameId in ids }
    val theirStats = stats.filter { it.opponent }
    val ourStats = stats.filter { !it.opponent }

    val wins = theirGames.count { (it.opponentScore ?: 0) > (it.teamScore ?: 0) }
    val losses = theirGames.size - wins

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(opponentName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = ListContentPadding,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                SectionCard("Against Kansas") {
                    Text(
                        "$wins-$losses in ${theirGames.size} " +
                            (if (theirGames.size == 1) "meeting" else "meetings"),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    val meetings = theirGames.size.coerceAtLeast(1)
                    val scored = theirGames.sumOf { it.opponentScore ?: 0 }.toDouble() / meetings
                    val allowed = theirGames.sumOf { it.teamScore ?: 0 }.toDouble() / meetings
                    Text(
                        "${formatPerGame(scored)} scored, ${formatPerGame(allowed)} allowed per game",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Shooting needs the official team totals. Without them the card is
            // left out rather than built from summed player lines, which run
            // short of the team's real rebounds and turnovers.
            if (theirStats.isNotEmpty()) {
                item {
                    SectionCard("How they shot") {
                        val them = theirStats.efficiency()
                        val us = ourStats.takeIf { it.isNotEmpty() }?.efficiency()
                        EfficiencyRow("eFG%", formatPct(them.effectiveFieldGoalPct),
                            us?.let { formatPct(it.effectiveFieldGoalPct) }, "KU")
                        EfficiencyRow("TS%", formatPct(them.trueShootingPct),
                            us?.let { formatPct(it.trueShootingPct) }, "KU")
                        EfficiencyRow("3PAr", formatPct(them.threePointAttemptRate),
                            us?.let { formatPct(it.threePointAttemptRate) }, "KU")
                        EfficiencyRow("FTr", formatPct(them.freeThrowRate),
                            us?.let { formatPct(it.freeThrowRate) }, "KU")
                        EfficiencyRow(
                            "A/TO",
                            them.assistToTurnover?.let { formatPerGame(it) } ?: "—",
                            us?.assistToTurnover?.let { formatPerGame(it) },
                            "KU"
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            "Press and hold a label for what it means.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item {
                Text(
                    "Results",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            items(theirGames, key = { it.id }) { game ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                game.date,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                // The site is KU's, so it reads inverted here.
                                listOfNotNull(
                                    when (game.siteOrLegacy) {
                                        "home" -> "at Kansas"
                                        "away" -> "at home"
                                        "neutral" -> "neutral site"
                                        else -> null
                                    },
                                    game.venue,
                                    game.overtime?.let { if (it > 1) "${it}OT" else "OT" }
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        val theirs = game.opponentScore ?: 0
                        val ours = game.teamScore ?: 0
                        Text(
                            "${if (theirs > ours) "W" else "L"} $theirs-$ours",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            // Their win is our loss, so the colors are KU's.
                            color = if (theirs > ours) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            if (players.isNotEmpty()) {
                item {
                    Text(
                        "Players vs Kansas",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                items(players, key = { it.name }) { player ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            JerseyBadge(player.jerseyNumber)
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 12.dp)
                            ) {
                                Text(
                                    listOf(player.name, player.position)
                                        .filter { it.isNotBlank() }.joinToString(" · "),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                                val t = player.totals
                                Text(
                                    "${t.points} PTS · ${t.totalRebounds} REB · ${t.assists} AST" +
                                        if (player.games > 1) {
                                            " · ${formatPerGame(t.pointsPerGame)} per game"
                                        } else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    "${formatShots(t.fieldGoalsMade, t.fieldGoalsAttempted)} FG · " +
                                        "${formatShots(t.threePointsMade, t.threePointsAttempted)} 3PT · " +
                                        "${formatShots(t.freeThrowsMade, t.freeThrowsAttempted)} FT",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
