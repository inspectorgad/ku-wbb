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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.data.Game
import com.example.data.GameTeamStats
import com.example.stats.QuarterSplit
import com.example.stats.Split
import com.example.stats.conferenceSplits
import com.example.stats.cumulativeQuarterMargin
import com.example.stats.efficiency
import com.example.stats.formatPct
import com.example.stats.formatPerGame
import com.example.stats.halftimeSplits
import com.example.stats.marginSplits
import com.example.stats.played
import com.example.stats.quarterSplits
import com.example.stats.recordOf
import com.example.stats.siteSplits

/**
 * How the season actually went: shooting efficiency beyond raw percentages,
 * where the games were won, and which quarter decided them.
 *
 * All the arithmetic lives in stats/Efficiency.kt and stats/Splits.kt as plain
 * functions, so it is tested without a screen; this file only arranges it.
 */
@Composable
fun SeasonScreen(
    games: List<Game>,
    teamStats: List<GameTeamStats>,
    modifier: Modifier = Modifier
) {
    val seasons = games.map { it.season }.distinct().sortedDescending()
    var selectedSeason by rememberSaveable { mutableStateOf<String?>(null) }
    // Default to the newest season that has actually been played, so the tab
    // does not open empty in October.
    val season = selectedSeason
        ?: seasons.firstOrNull { s -> played(games.filter { it.season == s }).isNotEmpty() }
        ?: seasons.firstOrNull()

    if (season == null) {
        EmptyState(
            title = "No season data yet",
            subtitle = "Shooting and splits appear once games have been played.",
            modifier = modifier.fillMaxSize()
        )
        return
    }

    val seasonGames = games.filter { it.season == season }
    val finished = played(seasonGames)
    val gameIds = finished.map { it.id }.toSet()
    val ourStats = teamStats.filter { it.gameId in gameIds && !it.opponent }
    val theirStats = teamStats.filter { it.gameId in gameIds && it.opponent }

    Column(modifier = modifier.fillMaxSize()) {
        if (seasons.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                seasons.forEach { s ->
                    FilterChip(
                        selected = season == s,
                        onClick = { selectedSeason = s },
                        label = { Text(s) }
                    )
                }
            }
        }

        if (finished.isEmpty()) {
            EmptyState(
                title = "No games played yet in $season",
                subtitle = "Shooting and splits fill in as the season goes.",
                modifier = Modifier.fillMaxSize()
            )
            return@Column
        }

        LazyColumn(
            contentPadding = ListContentPadding,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                SectionCard("Record — $season") {
                    val r = recordOf(finished)
                    Text(
                        "$r · ${formatPerGame(r.pointsForPerGame)} scored, " +
                            "${formatPerGame(r.pointsAgainstPerGame)} allowed",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Explainable("Margin") {
                        Text(
                            "Margin ${if (r.margin >= 0) "+" else ""}${formatPerGame(r.margin)} per game",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Shooting efficiency needs the official team totals; without them
            // the card is left out rather than built from summed player lines.
            if (ourStats.isNotEmpty()) {
                item {
                    SectionCard("Shooting") {
                        val us = ourStats.efficiency()
                        val them = theirStats.takeIf { it.isNotEmpty() }?.efficiency()
                        EfficiencyRow("eFG%", formatPct(us.effectiveFieldGoalPct),
                            them?.let { formatPct(it.effectiveFieldGoalPct) })
                        EfficiencyRow("TS%", formatPct(us.trueShootingPct),
                            them?.let { formatPct(it.trueShootingPct) })
                        EfficiencyRow("3PAr", formatPct(us.threePointAttemptRate),
                            them?.let { formatPct(it.threePointAttemptRate) })
                        EfficiencyRow("FTr", formatPct(us.freeThrowRate),
                            them?.let { formatPct(it.freeThrowRate) })
                        EfficiencyRow(
                            "A/TO",
                            us.assistToTurnover?.let { formatPerGame(it) } ?: "—",
                            them?.assistToTurnover?.let { formatPerGame(it) }
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

            item { SplitsCard("Where the games were played", siteSplits(finished)) }
            conferenceSplits(finished).takeIf { it.isNotEmpty() }?.let {
                item { SplitsCard("Conference and outside it", it) }
            }
            item { SplitsCard("How close they were", marginSplits(finished)) }
            halftimeSplits(finished).takeIf { it.isNotEmpty() }?.let {
                item { SplitsCard("At the half", it) }
            }

            quarterSplits(finished).takeIf { it.isNotEmpty() }?.let { quarters ->
                item {
                    SectionCard("By quarter") {
                        QuarterTable(quarters)
                        Spacer(modifier = Modifier.height(8.dp))
                        val running = cumulativeQuarterMargin(finished)
                        Text(
                            "Running margin: " + running.joinToString(" · ") { (label, m) ->
                                "$label ${if (m >= 0) "+" else ""}$m"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(6.dp))
            content()
        }
    }
}

@Composable
private fun EfficiencyRow(label: String, ours: String, theirs: String?) {
    Explainable(label, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                modifier = Modifier.width(64.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                ours,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            if (theirs != null) {
                Text(
                    "opp $theirs",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End
                )
            }
        }
    }
}

@Composable
private fun SplitsCard(title: String, splits: List<Split>) {
    SectionCard(title) {
        splits.forEach { split ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    split.label,
                    modifier = Modifier.weight(1.4f),
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    split.record.toString(),
                    modifier = Modifier.weight(0.6f),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "${formatPerGame(split.record.pointsForPerGame)}–" +
                        formatPerGame(split.record.pointsAgainstPerGame),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End
                )
            }
        }
    }
}

@Composable
private fun QuarterTable(quarters: List<QuarterSplit>) {
    Row {
        Text("", modifier = Modifier.weight(0.8f), style = MaterialTheme.typography.labelSmall)
        for (h in listOf("For", "Opp", "W-L-T")) {
            Text(
                h,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.End
            )
        }
    }
    HorizontalDivider()
    quarters.forEach { q ->
        Row(modifier = Modifier.padding(vertical = 3.dp)) {
            Text(
                q.label,
                modifier = Modifier.weight(0.8f),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                formatPerGame(q.forPerGame),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.End
            )
            Text(
                formatPerGame(q.againstPerGame),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End
            )
            Text(
                "${q.won}-${q.lost}${if (q.tied > 0) "-${q.tied}" else ""}",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.End
            )
        }
    }
}
