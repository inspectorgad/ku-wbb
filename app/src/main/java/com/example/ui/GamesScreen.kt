package com.example.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.data.Game
import com.example.data.GameTeamStats
import com.example.data.OpponentStatLine
import com.example.data.Player
import com.example.data.StatLine
import com.example.stats.formatPct
import com.example.stats.summarize

/**
 * "vs " at home, "at " on the road, "vs " for a neutral floor (the opponent is
 * not hosting either), and nothing at all when the site is unknown — better a
 * bare opponent name than a wrong one.
 */
fun siteLabel(site: String?): String = when (site) {
    "home", "neutral" -> "vs "
    "away" -> "at "
    else -> ""
}

/** Short suffix naming a neutral floor, e.g. " (neutral · T-Mobile Center)". */
fun neutralNote(game: Game): String =
    if (game.siteOrLegacy != "neutral") ""
    else " (neutral" + (game.venue?.let { " · $it" } ?: "") + ")"

/**
 * The second line for a game that has not been played: tip time, broadcaster
 * and the event it belongs to, whichever of those the schedule has given up so
 * far. Empty when it knows none of them, so the row simply omits the line.
 */
fun fixtureNote(game: Game): String = listOfNotNull(
    game.tipTime?.let { "$it${if (game.tv != null || game.event != null) "" else " tip"}" },
    game.tv?.let { "TV $it" },
    game.event,
).joinToString(" · ")

@Composable
fun GamesScreen(
    games: List<Game>,
    statLines: List<StatLine>,
    onSaveGame: (Game) -> Unit,
    onOpenGame: (Game) -> Unit,
    modifier: Modifier = Modifier
) {
    var showAddDialog by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize()) {
        if (games.isEmpty()) {
            EmptyState(
                title = "No games yet",
                subtitle = "Pull down to sync the season, or add games manually to track stats.",
                modifier = Modifier.align(Alignment.Center)
            )
        } else {
            LazyColumn(
                contentPadding = ListContentPadding,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(games, key = { it.id }) { game ->
                    val lineCount = statLines.count { it.gameId == game.id }
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenGame(game) }
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "${siteLabel(game.siteOrLegacy)}${game.opponent}",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    "${game.date} · ${game.season}" +
                                        neutralNote(game) +
                                        if (lineCount > 0) " · $lineCount player${if (lineCount == 1) "" else "s"}" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                game.periodScores?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                // Upcoming games carry a tip time instead of a
                                // line score; once played the tip is dropped.
                                fixtureNote(game).takeIf { it.isNotBlank() }?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            ResultText(game)
                        }
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = { showAddDialog = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = "Add game")
        }
    }

    if (showAddDialog) {
        GameDialog(
            game = null,
            defaultSeason = games.maxByOrNull { it.date }?.season ?: "",
            onDismiss = { showAddDialog = false },
            onSave = {
                onSaveGame(it)
                showAddDialog = false
            }
        )
    }
}

@Composable
private fun ResultText(game: Game) {
    val us = game.teamScore
    val them = game.opponentScore
    if (us == null || them == null) {
        Text(
            "No result",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        val won = us > them
        Text(
            "${if (won) "W" else "L"} $us–$them",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = if (won) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.error
        )
    }
}

@Composable
fun GameDialog(
    game: Game?,
    defaultSeason: String,
    onDismiss: () -> Unit,
    onSave: (Game) -> Unit
) {
    var date by remember { mutableStateOf(game?.date ?: "") }
    var opponent by remember { mutableStateOf(game?.opponent ?: "") }
    var season by remember { mutableStateOf(game?.season ?: defaultSeason) }
    var teamScore by remember { mutableStateOf(game?.teamScore?.toString() ?: "") }
    var oppScore by remember { mutableStateOf(game?.opponentScore?.toString() ?: "") }
    var periodScores by remember { mutableStateOf(game?.periodScores ?: "") }
    // A hand-added game defaults to a home game; editing an existing one keeps
    // whatever it has (an unknown site becomes "home" only if the user saves).
    // Neutral is scraper-derived from the venue and not offered here, so
    // editing a neutral game keeps it neutral unless the switch is touched.
    var site by remember { mutableStateOf(game?.siteOrLegacy ?: "home") }
    val home = site == "home"

    val dateValid = Regex("""\d{4}-\d{2}-\d{2}""").matches(date)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (game == null) "Add Game" else "Edit Game") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = date,
                    onValueChange = { date = it.take(10) },
                    label = { Text("Date (YYYY-MM-DD)") },
                    singleLine = true,
                    isError = date.isNotEmpty() && !dateValid,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = opponent,
                    onValueChange = { opponent = it },
                    label = { Text("Opponent") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = season,
                    onValueChange = { season = it },
                    label = { Text("Season (e.g. 2025-26)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(
                        label = "Our score",
                        value = teamScore,
                        onValueChange = { teamScore = it },
                        modifier = Modifier.weight(1f)
                    )
                    NumberField(
                        label = "Their score",
                        value = oppScore,
                        onValueChange = { oppScore = it },
                        modifier = Modifier.weight(1f)
                    )
                }
                OutlinedTextField(
                    value = periodScores,
                    onValueChange = { periodScores = it },
                    label = { Text("Quarter scores (20-15, 18-12, …)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when (site) {
                            "home" -> "Home game"
                            "neutral" -> "Neutral site"
                            else -> "Away game"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = home,
                        onCheckedChange = { site = if (it) "home" else "away" }
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = dateValid && opponent.isNotBlank() && season.isNotBlank(),
                onClick = {
                    onSave(
                        Game(
                            id = game?.id ?: 0,
                            date = date,
                            opponent = opponent.trim(),
                            season = season.trim(),
                            home = home,
                            site = site,
                            venue = game?.venue,
                            city = game?.city,
                            teamScore = teamScore.toIntOrNull(),
                            opponentScore = oppScore.toIntOrNull(),
                            periodScores = periodScores.trim().ifBlank { null }
                        )
                    )
                }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameDetailScreen(
    game: Game,
    players: List<Player>,
    statLines: List<StatLine>,
    opponentLines: List<OpponentStatLine> = emptyList(),
    teamStats: List<GameTeamStats> = emptyList(),
    onSaveGame: (Game) -> Unit,
    onDeleteGame: (Game) -> Unit,
    onSaveStatLine: (StatLine) -> Unit,
    onDeleteStatLine: (StatLine) -> Unit,
    onBack: () -> Unit
) {
    var showEditDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var editingLineFor by remember { mutableStateOf<Player?>(null) }

    val gameLines = statLines.filter { it.gameId == game.id }
    val linesByPlayer = gameLines.associateBy { it.playerId }
    val oppLines = opponentLines.filter { it.gameId == game.id }.sortedByDescending { it.points }
    val ourTotals = teamStats.firstOrNull { it.gameId == game.id && !it.opponent }
    val theirTotals = teamStats.firstOrNull { it.gameId == game.id && it.opponent }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${siteLabel(game.siteOrLegacy)}${game.opponent}") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showEditDialog = true }) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit game")
                    }
                    IconButton(onClick = { showDeleteConfirm = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete game")
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
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "${game.date} · ${game.season}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            game.periodScores?.let {
                                Text(
                                    "Quarters: $it" +
                                        (game.overtime?.let { n ->
                                            " · ${if (n > 1) "${n}OT" else "OT"}"
                                        } ?: ""),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            listOfNotNull(
                                game.venue,
                                game.city,
                                if (game.conference) "Big 12" else null,
                            ).takeIf { it.isNotEmpty() }?.let {
                                Text(
                                    it.joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                "Tap a player below to enter their stat line.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        ResultText(game)
                    }
                }
            }

            // Official team totals for both sides. Not the sum of the player
            // lines: team rebounds and team turnovers belong to no individual.
            if (ourTotals != null && theirTotals != null) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                "Team Stats",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            TeamStatsComparison(
                                ours = ourTotals,
                                theirs = theirTotals,
                                opponent = game.opponent
                            )
                        }
                    }
                }
            }

            if (oppLines.isNotEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                "${game.opponent} box score",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            OpponentBoxScore(oppLines)
                        }
                    }
                }
            }

            if (players.isEmpty()) {
                item {
                    EmptyState(
                        title = "No players on the roster",
                        subtitle = "Add players on the Roster tab first, then record their stats here."
                    )
                }
            } else {
                // Former players only clutter stat entry unless they actually
                // played in this game (e.g. seeded 2025-26 box scores).
                val relevant = players.filter { it.active || it.id in linesByPlayer }
                items(relevant, key = { it.id }) { player ->
                    val line = linesByPlayer[player.id]
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { editingLineFor = player }
                    ) {
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
                                Text(
                                    line?.let { summarize(it) } ?: "Did not play — tap to add",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (line != null) {
                                IconButton(onClick = { onDeleteStatLine(line) }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Remove stat line",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    editingLineFor?.let { player ->
        StatLineDialog(
            player = player,
            existing = linesByPlayer[player.id],
            gameId = game.id,
            onDismiss = { editingLineFor = null },
            onSave = {
                onSaveStatLine(it)
                editingLineFor = null
            }
        )
    }

    if (showEditDialog) {
        GameDialog(
            game = game,
            defaultSeason = game.season,
            onDismiss = { showEditDialog = false },
            onSave = {
                onSaveGame(it)
                showEditDialog = false
            }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete this game?") },
            text = { Text("This removes the game and every stat line recorded for it. This cannot be undone.") },
            confirmButton = {
                Button(onClick = { onDeleteGame(game) }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
fun StatLineDialog(
    player: Player,
    existing: StatLine?,
    gameId: Long,
    onDismiss: () -> Unit,
    onSave: (StatLine) -> Unit
) {
    fun init(value: Int?) = value?.takeIf { it != 0 }?.toString() ?: ""

    var minutes by remember { mutableStateOf(init(existing?.minutes)) }
    var points by remember { mutableStateOf(init(existing?.points)) }
    var fgm by remember { mutableStateOf(init(existing?.fieldGoalsMade)) }
    var fga by remember { mutableStateOf(init(existing?.fieldGoalsAttempted)) }
    var tpm by remember { mutableStateOf(init(existing?.threePointsMade)) }
    var tpa by remember { mutableStateOf(init(existing?.threePointsAttempted)) }
    var ftm by remember { mutableStateOf(init(existing?.freeThrowsMade)) }
    var fta by remember { mutableStateOf(init(existing?.freeThrowsAttempted)) }
    var oreb by remember { mutableStateOf(init(existing?.offensiveRebounds)) }
    var reb by remember { mutableStateOf(init(existing?.totalRebounds)) }
    var ast by remember { mutableStateOf(init(existing?.assists)) }
    var turnovers by remember { mutableStateOf(init(existing?.turnovers)) }
    var stl by remember { mutableStateOf(init(existing?.steals)) }
    var blk by remember { mutableStateOf(init(existing?.blocks)) }
    var pf by remember { mutableStateOf(init(existing?.personalFouls)) }
    var started by remember { mutableStateOf(existing?.started ?: false) }

    fun num(s: String) = s.toIntOrNull() ?: 0

    val shotError = when {
        num(fgm) > num(fga) -> "Field goals made can't exceed attempts."
        num(tpm) > num(tpa) -> "Threes made can't exceed attempts."
        num(ftm) > num(fta) -> "Free throws made can't exceed attempts."
        num(tpm) > num(fgm) -> "Threes made can't exceed field goals made."
        num(oreb) > num(reb) -> "Offensive rebounds can't exceed total rebounds."
        else -> null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${player.name} — Game Line") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatFieldRow(
                    "MIN" to minutes to { v: String -> minutes = v },
                    "PTS" to points to { v: String -> points = v },
                    "PF" to pf to { v: String -> pf = v }
                )
                StatFieldRow(
                    "FGM" to fgm to { v: String -> fgm = v },
                    "FGA" to fga to { v: String -> fga = v },
                    "3PM" to tpm to { v: String -> tpm = v }
                )
                StatFieldRow(
                    "3PA" to tpa to { v: String -> tpa = v },
                    "FTM" to ftm to { v: String -> ftm = v },
                    "FTA" to fta to { v: String -> fta = v }
                )
                StatFieldRow(
                    "OREB" to oreb to { v: String -> oreb = v },
                    "REB" to reb to { v: String -> reb = v },
                    "AST" to ast to { v: String -> ast = v }
                )
                StatFieldRow(
                    "TO" to turnovers to { v: String -> turnovers = v },
                    "STL" to stl to { v: String -> stl = v },
                    "BLK" to blk to { v: String -> blk = v }
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Started",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(checked = started, onCheckedChange = { started = it })
                }
                shotError?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = shotError == null,
                onClick = {
                    onSave(
                        StatLine(
                            id = existing?.id ?: 0,
                            playerId = player.id,
                            gameId = gameId,
                            minutes = num(minutes),
                            fieldGoalsMade = num(fgm),
                            fieldGoalsAttempted = num(fga),
                            threePointsMade = num(tpm),
                            threePointsAttempted = num(tpa),
                            freeThrowsMade = num(ftm),
                            freeThrowsAttempted = num(fta),
                            offensiveRebounds = num(oreb),
                            totalRebounds = num(reb),
                            assists = num(ast),
                            turnovers = num(turnovers),
                            steals = num(stl),
                            blocks = num(blk),
                            personalFouls = num(pf),
                            points = num(points),
                            started = started
                        )
                    )
                }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun StatFieldRow(
    vararg fields: Pair<Pair<String, String>, (String) -> Unit>
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        fields.forEach { (labelAndValue, onChange) ->
            val (label, value) = labelAndValue
            NumberField(
                label = label,
                value = value,
                onValueChange = onChange,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/** Kansas against the opponent, official totals side by side. */
@Composable
private fun TeamStatsComparison(
    ours: GameTeamStats,
    theirs: GameTeamStats,
    opponent: String
) {
    val shooting = { m: Int, a: Int ->
        if (a == 0) "0-0" else "$m-$a (${formatPct(m.toDouble() / a)})"
    }
    val rows = listOf(
        "FG" to (shooting(ours.fgm, ours.fga) to shooting(theirs.fgm, theirs.fga)),
        "3PT" to (shooting(ours.tpm, ours.tpa) to shooting(theirs.tpm, theirs.tpa)),
        "FT" to (shooting(ours.ftm, ours.fta) to shooting(theirs.ftm, theirs.fta)),
        "Rebounds" to ("${ours.reb} (${ours.oreb} off)" to "${theirs.reb} (${theirs.oreb} off)"),
        "Assists" to ("${ours.ast}" to "${theirs.ast}"),
        "Turnovers" to ("${ours.to}" to "${theirs.to}"),
        "Steals" to ("${ours.stl}" to "${theirs.stl}"),
        "Blocks" to ("${ours.blk}" to "${theirs.blk}"),
        "Fouls" to ("${ours.pf}" to "${theirs.pf}"),
    )
    Row {
        Text(
            "", modifier = Modifier.weight(1.1f),
            style = MaterialTheme.typography.labelSmall
        )
        Text(
            "Kansas", modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.End
        )
        Text(
            opponent.take(12), modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.End
        )
    }
    HorizontalDivider()
    rows.forEach { (label, values) ->
        Row(modifier = Modifier.padding(vertical = 3.dp)) {
            Text(
                label, modifier = Modifier.weight(1.1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                values.first, modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.End
            )
            Text(
                values.second, modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End
            )
        }
    }
}

/** The opponent's players, best scorer first. Read-only: not our roster. */
@Composable
private fun OpponentBoxScore(lines: List<OpponentStatLine>) {
    Row {
        Text(
            "Player", modifier = Modifier.weight(2f),
            style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold
        )
        for (h in listOf("MIN", "PTS", "REB", "AST")) {
            Text(
                h, modifier = Modifier.weight(0.7f),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold, textAlign = TextAlign.End
            )
        }
    }
    HorizontalDivider()
    lines.forEach { l ->
        Row(modifier = Modifier.padding(vertical = 3.dp)) {
            Text(
                (if (l.started) "• " else "") + l.playerName,
                modifier = Modifier.weight(2f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1
            )
            for (v in listOf(l.minutes, l.points, l.rebounds, l.assists)) {
                Text(
                    "$v", modifier = Modifier.weight(0.7f),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.End
                )
            }
        }
    }
    Text(
        "• started",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp)
    )
}
