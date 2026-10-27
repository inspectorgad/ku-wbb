package com.example.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * Plain-English definitions for the abbreviations and derived stats.
 *
 * A box score is nineteen columns of initials and the efficiency figures are
 * worse — nobody should have to already know what TS% means to read their own
 * team's season. Press and hold anything with a definition to get one.
 */
val GLOSSARY: Map<String, String> = mapOf(
    // Box score — these are exactly the STAT_COLUMNS headings, and a test
    // asserts none of them is left without a definition.
    "GP" to "Games played.",
    "MIN" to "Minutes played.",
    "PTS" to "Points scored.",
    "PPG" to "Points per game.",
    "RPG" to "Rebounds per game.",
    "OR" to "Offensive rebounds — a missed shot recovered by the shooting team, " +
        "giving it another possession.",
    "FG" to "Field goals: made and attempted. Every shot from the floor, twos and threes alike.",
    "FG%" to "Share of field goal attempts that went in. Counts a three and a layup the same, which is what eFG% corrects.",
    "3PT" to "Three-pointers: made and attempted.",
    "3P%" to "Share of three-point attempts that went in.",
    "FT" to "Free throws: made and attempted.",
    "FT%" to "Share of free throws that went in.",
    "REB" to "Total rebounds, offensive and defensive.",
    "AST" to "Assists: passes that led directly to a made basket.",
    "TO" to "Turnovers: possessions lost without a shot attempt.",
    "STL" to "Steals: a turnover forced by taking the ball.",
    "BLK" to "Blocks: shots deflected before reaching the basket.",
    "PF" to "Personal fouls.",
    "GS" to "Games started.",

    // Derived
    "eFG%" to "Effective field goal percentage. Like FG%, but a three counts for " +
        "the extra half a basket it is worth: (FGM + 0.5 x 3PM) / FGA. A team that " +
        "shoots a lot of threes can score well on a modest FG%.",
    "TS%" to "True shooting percentage. Points per scoring attempt, counting free " +
        "throws: PTS / (2 x (FGA + 0.44 x FTA)). The 0.44 estimates how many trips " +
        "to the line an attempt represents, since and-ones and three-shot fouls mean " +
        "attempts and trips are not the same thing.",
    "3PAr" to "Three-point attempt rate: what share of shots come from behind the arc.",
    "FTr" to "Free throw rate: free throw attempts per field goal attempt — how " +
        "often attacking turns into trips to the line.",
    "A/TO" to "Assists per turnover. Above 1.0 means more possessions created than lost.",
    "Margin" to "Points scored minus points allowed, per game.",

    // Standings and rankings
    "NET" to "The NCAA Evaluation Tool: the committee's own team rating, covering " +
        "every Division I team. Lower is better.",
    "AP" to "The Associated Press top 25, voted by sportswriters. Teams outside it " +
        "are unranked.",
    "PCT" to "Winning percentage in conference play.",
    "Conf" to "Record against Big 12 opponents in the regular season. The conference " +
        "tournament is not part of it.",

    // Site and classification
    "Neutral" to "Played on neither team's floor — a tournament or a showcase. " +
        "The NCAA counts it separately from home and away.",
    "OT" to "Overtime. A five-minute period played when the score is level after " +
        "four quarters.",
)

/** The definition for a label, if there is one. */
fun explain(label: String): String? = GLOSSARY[label]

/**
 * Wraps content so a long press explains it. Falls back to doing nothing when
 * the label has no definition, so callers need not check first.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Explainable(
    label: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val definition = explain(label)
    var showing by remember { mutableStateOf(false) }

    if (definition == null && onClick == null) {
        content()
        return
    }
    Column(
        modifier = modifier.combinedClickable(
            enabled = definition != null || onClick != null,
            onClick = { onClick?.invoke() },
            onLongClick = { if (definition != null) showing = true }
        )
    ) { content() }

    if (showing && definition != null) {
        AlertDialog(
            onDismissRequest = { showing = false },
            title = { Text(label, style = MaterialTheme.typography.titleMedium) },
            text = { Text(definition, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = { showing = false }) { Text("Got it") }
            }
        )
    }
}
