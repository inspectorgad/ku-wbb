package com.example.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.data.SeasonSimulator
import com.example.text.reformat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * How the season might finish: the simulation's three lines, and a Win/Loss
 * toggle on every game Kansas has left.
 *
 * The same wording as the dashboard's simulator.js, because the two are
 * showing the same numbers and should not describe them differently.
 */
private const val EXPLAIN =
    "The rest of the season played out 20,000 times. Each remaining game is won or lost at " +
        "random with the win model's chance for it, and these numbers count what happened. " +
        "Set a game to Win or Loss to see what that would change. The other Big 12 teams' " +
        "remaining opponents are not published game by game, so their games other than " +
        "against Kansas are played against an average Big 12 side, and ties in the table are " +
        "settled by a coin toss rather than the Big 12's tiebreakers. The conference " +
        "tournament takes all 16 teams on a neutral floor, with a double bye for the top four " +
        "seeds and a bye for the next four, and its winner takes the Big 12's automatic place " +
        "in the NCAA tournament. There is no at-large estimate: that turns on the NET, which " +
        "the NCAA builds by machine learning and publishes no formula for, so it cannot " +
        "honestly be projected. Estimates, not a forecast of what the committee will do."

fun ordinal(n: Int): String {
    val t = n % 100
    if (t in 11..13) return "${n}th"
    return n.toString() + when (n % 10) {
        1 -> "st"
        2 -> "nd"
        3 -> "rd"
        else -> "th"
    }
}

/** The three lines, kept apart from the drawing so a test can read them. */
fun simulationLines(r: SeasonSimulator.Result): List<String> {
    fun record(w: Int) = "$w-${r.games - w}"
    return listOf(
        "Projected record ${record(r.winsMid)} (likely ${record(r.winsLow)} to ${record(r.winsHigh)})",
        "Big 12: finish around ${ordinal(r.seedMid)} (${ordinal(r.seedLow)} to ${ordinal(r.seedHigh)})" +
            " · double bye ${chance(r.top4)} · a bye ${chance(r.top8)} · title ${chance(r.title)}",
        "Big 12 tournament ${chance(r.autoBid)} — the automatic NCAA place",
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimulatorScreen(inputs: SeasonSimulator.Inputs, onBack: () -> Unit) {
    // Not rememberSaveable: a Map is not something the saver handles, and the
    // simulation is cheap to redo from scratch.
    val forced = remember { mutableStateMapOf<Int, String>() }
    var result by remember { mutableStateOf<SeasonSimulator.Result?>(null) }

    // 20,000 seasons is tens of milliseconds, but it is not nothing, and it is
    // not work for the frame-drawing thread.
    LaunchedEffect(inputs, forced.toMap()) {
        result = withContext(Dispatchers.Default) {
            SeasonSimulator.simulate(inputs, forced.toMap())
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("How the season might finish") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(AppIcons.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { inner ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                val r = result
                SectionCard(
                    if (forced.isEmpty()) "If the rest of the season goes as the model expects"
                    else "With ${forced.size} result${if (forced.size == 1) "" else "s"} set by you"
                ) {
                    if (r == null) {
                        Text("Working it out…", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        for (line in simulationLines(r)) {
                            Text(line, style = MaterialTheme.typography.bodyMedium)
                            Spacer(modifier = Modifier.height(2.dp))
                        }
                    }
                    if (forced.isNotEmpty()) {
                        TextButton(onClick = { forced.clear() }) { Text("Reset to the model") }
                    }
                }
            }

            itemsIndexed(inputs.remaining) { i, g ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            reformat(g.date, "yyyy-MM-dd", "MMM d") ?: g.date,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(56.dp)
                        )
                        Text(
                            (if (g.site == "away") "at " else "vs ") + g.opponent,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            chance(g.p),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.End,
                            modifier = Modifier.width(48.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        for (v in listOf("W", "L")) {
                            FilterChip(
                                selected = forced[i] == v,
                                onClick = { if (forced[i] == v) forced.remove(i) else forced[i] = v },
                                label = { Text(if (v == "W") "Win" else "Loss") }
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                    }
                }
            }

            item {
                var note = EXPLAIN
                if (inputs.unrated.isNotEmpty()) {
                    note += " Left out, having no rating to play with: " +
                        inputs.unrated.joinToString(", ") + "."
                }
                Explanation(note)
            }
        }
    }
}
