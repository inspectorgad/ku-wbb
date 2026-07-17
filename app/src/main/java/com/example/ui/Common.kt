package com.example.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.stats.BasketballTotals
import com.example.stats.formatPct
import com.example.stats.formatPerGame
import com.example.stats.formatShots

// Column label to cell width: the made-attempted shot columns need extra room.
val STAT_COLUMNS = listOf(
    "GP" to 40, "GS" to 40, "MIN" to 48, "PTS" to 48, "PPG" to 48,
    "FG" to 64, "FG%" to 52, "3PT" to 56, "3P%" to 52, "FT" to 56, "FT%" to 52,
    "OR" to 40, "REB" to 48, "RPG" to 48, "AST" to 44, "TO" to 40,
    "STL" to 44, "BLK" to 44, "PF" to 40
)

fun statValues(t: BasketballTotals): List<String> = listOf(
    t.games.toString(), t.gamesStarted.toString(), t.minutes.toString(),
    t.points.toString(), formatPerGame(t.pointsPerGame),
    formatShots(t.fieldGoalsMade, t.fieldGoalsAttempted), formatPct(t.fieldGoalPercentage),
    formatShots(t.threePointsMade, t.threePointsAttempted), formatPct(t.threePointPercentage),
    formatShots(t.freeThrowsMade, t.freeThrowsAttempted), formatPct(t.freeThrowPercentage),
    t.offensiveRebounds.toString(), t.totalRebounds.toString(),
    formatPerGame(t.reboundsPerGame), t.assists.toString(), t.turnovers.toString(),
    t.steals.toString(), t.blocks.toString(), t.personalFouls.toString()
)

/**
 * A horizontally scrollable stats table. Each row is a label (e.g. season name)
 * plus one [BasketballTotals]. The label column stays compact; stat cells use
 * the per-column widths from [STAT_COLUMNS].
 */
@Composable
fun StatsTable(
    rows: List<Pair<String, BasketballTotals>>,
    modifier: Modifier = Modifier,
    labelWidth: Int = 84
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TableCell("", width = labelWidth.dp, header = true)
            STAT_COLUMNS.forEach { (label, w) -> TableCell(label, width = w.dp, header = true) }
        }
        HorizontalDivider()
        rows.forEach { (label, totals) ->
            Row(
                modifier = Modifier.padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TableCell(label, width = labelWidth.dp, header = true, align = TextAlign.Start)
                statValues(totals).forEachIndexed { i, value ->
                    TableCell(value, width = STAT_COLUMNS[i].second.dp)
                }
            }
        }
    }
}

@Composable
private fun TableCell(
    text: String,
    width: Dp,
    header: Boolean = false,
    align: TextAlign = TextAlign.Center
) {
    Text(
        text = text,
        modifier = Modifier
            .width(width)
            .padding(vertical = 4.dp),
        fontSize = 12.sp,
        fontWeight = if (header) FontWeight.Bold else FontWeight.Normal,
        textAlign = align,
        maxLines = 1,
        color = if (header) MaterialTheme.colorScheme.onSurface
        else MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** Compact numeric entry field used in the stat line editor. */
@Composable
fun NumberField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = { new -> onValueChange(new.filter { it.isDigit() }.take(3)) },
        label = { Text(label, fontSize = 11.sp) },
        modifier = modifier,
        singleLine = true,
        textStyle = TextStyle(fontSize = 14.sp, textAlign = TextAlign.Center),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
    )
}

@Composable
fun EmptyState(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

val ListContentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp)
