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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.Game
import com.example.data.GameTeamStats
import com.example.data.OpponentRosterPlayer
import com.example.data.OpponentStatLine
import com.example.data.sameTeam
import com.example.stats.efficiency
import com.example.stats.formatPct
import com.example.stats.formatPerGame
import com.example.stats.formatShots
import com.example.stats.opponentPlayers
import com.example.stats.rosterFor
import com.example.stats.scheduledOpponents
import com.example.stats.summarizeOpponents

/**
 * Who Kansas played, and what they did in the meeting — and, below that, who
 * is still to come.
 *
 * Everything in the played half is scoped to games against Kansas — those are
 * the only box scores the app holds — and every figure is stated from the
 * opponent's side, so a row reads as their record against us. The caption says
 * so outright, because "Baylor 1-1" could otherwise be mistaken for their
 * season.
 *
 * The seasons offered include ones with nothing played yet. Before a season
 * tips off a box score can say nothing at all, but a current roster can, and
 * for the schools whose page is mapped one is held — so a season that has not
 * started is a list of squads rather than an empty screen. The default stays
 * on the newest season that has been played; a new one takes over on its own
 * once it has a result.
 */
@Composable
fun OpponentsScreen(
    games: List<Game>,
    teamStats: List<GameTeamStats>,
    roster: List<OpponentRosterPlayer>,
    onOpenOpponent: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val played = games.filter { it.teamScore != null }
        .map { it.season }.distinct().sortedDescending()
    val seasons = games.map { it.season }.distinct().sortedDescending()
    var selectedSeason by rememberSaveable { mutableStateOf<String?>(null) }
    val season = selectedSeason?.takeIf { it in seasons }
        ?: played.firstOrNull() ?: seasons.firstOrNull()

    val summaries = remember(games, teamStats, season) {
        summarizeOpponents(games, teamStats, season)
    }
    val upcoming = remember(games, roster, season) {
        scheduledOpponents(games, roster, season)
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (summaries.isEmpty() && upcoming.isEmpty()) {
            EmptyState(
                title = "No opponents yet",
                subtitle = "Each team Kansas plays appears here, before the game as a roster " +
                    "and after it as a result.",
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
            if (summaries.isNotEmpty()) {
                item(key = "caption") {
                    Text(
                        "Records and figures below are against Kansas only, not each opponent's " +
                            "own season, and are stated from their side.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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

            if (upcoming.isNotEmpty()) {
                item(key = "upcoming-header") {
                    Text(
                        if (summaries.isEmpty()) "On the schedule" else "Still to play",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                item(key = "upcoming-caption") {
                    Text(
                        "Rosters are each school's own, as it lists the team today — so they " +
                            "are right for a game not yet played, and say nothing about who " +
                            "was on the floor in an earlier season.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                items(upcoming, key = { "up-" + it.name }) { opponent ->
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
                                val first = opponent.games.first()
                                Text(
                                    listOfNotNull(
                                        first.date,
                                        when (first.siteOrLegacy) {
                                            "home" -> "at Kansas"
                                            "away" -> "at home"
                                            "neutral" -> "neutral site"
                                            else -> null
                                        },
                                        if (opponent.games.size > 1) {
                                            "${opponent.games.size} meetings"
                                        } else null
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    if (opponent.roster.isEmpty()) "—"
                                    else opponent.roster.size.toString(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    // Saying which it is matters: a school with
                                    // no roster here is one nobody has mapped a
                                    // page for, not one with no players.
                                    if (opponent.roster.isEmpty()) "no roster"
                                    else "players · ${opponent.fetchedAt}",
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpponentDetailScreen(
    opponentName: String,
    games: List<Game>,
    opponentLines: List<OpponentStatLine>,
    teamStats: List<GameTeamStats>,
    roster: List<OpponentRosterPlayer>,
    onBack: () -> Unit
) {
    val theirGames = games
        .filter { sameTeam(it.opponent, opponentName) && it.teamScore != null }
        .sortedByDescending { it.date }
    val scheduled = games
        .filter { sameTeam(it.opponent, opponentName) && it.teamScore == null }
        .sortedBy { it.date }
    val theirRoster = remember(roster, opponentName) { rosterFor(roster, opponentName) }
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
                        Icon(AppIcons.ArrowBack, contentDescription = "Back")
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
            if (theirGames.isNotEmpty()) {
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
            }

            if (scheduled.isNotEmpty()) {
                item {
                    SectionCard(if (scheduled.size == 1) "Scheduled" else "Scheduled meetings") {
                        scheduled.forEach { game ->
                            Text(
                                listOfNotNull(
                                    game.date,
                                    // The site is KU's, so it reads inverted.
                                    when (game.siteOrLegacy) {
                                        "home" -> "at Kansas"
                                        "away" -> "at home"
                                        "neutral" -> "neutral site"
                                        else -> null
                                    },
                                    game.venue,
                                    game.tipTime,
                                    game.tv
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
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

            // Gated like every other section here. Before this screen could be
            // opened for a team Kansas has not played, theirGames was never
            // empty and the header always had rows under it.
            if (theirGames.isNotEmpty()) {
                item {
                    Text(
                        "Results",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
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
                    Column(modifier = Modifier.padding(top = 8.dp)) {
                        Text(
                            "Players vs Kansas",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        // Say how many heights are known. A list with none on
                        // it otherwise reads as "this app does not carry
                        // opponent heights" rather than "this school's roster
                        // has moved on since these games".
                        val known = players.count { it.height.isNotBlank() }
                        if (known < players.size) {
                            Text(
                                if (theirRoster.isEmpty()) {
                                    "No heights here: no roster page is mapped for " +
                                        "$opponentName, and a box score carries none for " +
                                        "anyone but Kansas."
                                } else {
                                    "Heights for $known of ${players.size} — they come from " +
                                        "the current $opponentName roster, so they reach only " +
                                        "the players still on that team."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
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
                                    // A height that is not known is dropped
                                    // rather than shown blank; the caption
                                    // above says how many are missing and why.
                                    listOf(player.name, player.position, player.height)
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

            // Last, because for a team Kansas has played the results above are
            // what is wanted first. For one it has not, everything above is
            // empty and this is the screen.
            if (theirRoster.isNotEmpty()) {
                item(key = "roster-header") {
                    Column(modifier = Modifier.padding(top = 8.dp)) {
                        Text(
                            "Current roster",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "${theirRoster.size} players, from $opponentName's own page" +
                                theirRoster.first().fetchedAt
                                    .takeIf { it.isNotBlank() }
                                    ?.let { ", read $it" }.orEmpty() +
                                // The distinction the box scores above cannot
                                // make for themselves: this is the team now,
                                // not the team that played those games.
                                ". This is the squad today, not the one that " +
                                "played in an earlier season.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                items(theirRoster, key = { "r-" + it.name }) { player ->
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
                                    player.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                                // Only the labelled roster layouts carry the
                                // bios, so a blank one means that page did not
                                // print it — not that she has none.
                                val line = listOf(
                                    player.position, player.height, player.academicYear,
                                    player.hometown
                                ).filter { it.isNotBlank() }
                                if (line.isNotEmpty()) {
                                    Text(
                                        line.joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (player.lastSchool.isNotBlank()) {
                                    Text(
                                        player.lastSchool,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
