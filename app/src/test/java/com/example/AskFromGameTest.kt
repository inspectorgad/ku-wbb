package com.example

import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.data.Game
import com.example.ui.GameDetailScreen
import com.example.ui.previewQuestion
import com.example.ui.recapQuestion
import com.example.ui.theme.MyApplicationTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The button on a game that hands a question to "Ask about the team".
 *
 * Which question it is decides what the reader is charged for, so these pin
 * the two things a wrong one would not show: that the box score, not the date,
 * picks preview or recap, and that the question names the game the reader was
 * looking at. The screen is rendered on its own rather than through the whole
 * app, because it takes no composition locals and nothing else is needed to
 * press one button.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = RobolectricDeviceQualifiers.Pixel8)
class AskFromGameTest {

    @get:Rule val rule = createComposeRule()

    private fun game(
        date: String, opponent: String, us: Int? = null, them: Int? = null,
        site: String? = "home"
    ) = Game(
        id = 1, date = date, opponent = opponent, season = "2026-27",
        site = site, teamScore = us, opponentScore = them
    )

    private fun detail(game: Game, onAsk: ((String) -> Unit)?) {
        rule.setContent {
            MyApplicationTheme {
                Surface {
                    GameDetailScreen(
                        game = game,
                        players = emptyList(),
                        statLines = emptyList(),
                        onSaveGame = {},
                        onDeleteGame = {},
                        onSaveStatLine = {},
                        onDeleteStatLine = {},
                        onBack = {},
                        onAskClaude = onAsk
                    )
                }
            }
        }
    }

    /** Renders the detail screen, presses the button, returns what it asked. */
    private fun whatTheButtonAsks(game: Game, label: String): String {
        var asked: String? = null
        detail(game) { asked = it }
        rule.onNodeWithText(label).assertIsDisplayed()
        rule.onNodeWithText(label).performClick()
        rule.waitForIdle()
        return asked ?: error("the button handed nothing over")
    }

    @Test
    fun `a game with a final score offers a recap of that game`() {
        val asked = whatTheButtonAsks(
            game("2026-11-03", "Omaha", 78, 61), "Recap this game with Claude"
        )

        assertEquals(recapQuestion("2026-11-03", "vs ", "Omaha"), asked)
        assertTrue(asked.startsWith("Recap the 2026-11-03 game vs Omaha:"))
        // Basketball, and nothing the pack cannot answer: quarters rather than
        // sets, team totals rather than the performance goals this app has no
        // equivalent of.
        assertTrue(asked.contains("quarter"))
        assertTrue(asked.contains("team totals"))
        assertFalse(asked.contains("set "))
        assertFalse(asked.contains("goal"))
    }

    @Test
    fun `a game still to play offers a scouting report instead`() {
        val asked = whatTheButtonAsks(
            game("2026-11-14", "Nebraska", site = "neutral"), "Preview this game with Claude"
        )

        assertEquals(previewQuestion("2026-11-14", "vs ", "Nebraska"), asked)
        assertTrue(
            asked.startsWith("Give me a short scouting report for the 2026-11-14 game vs Nebraska:")
        )
        // The app's win estimate is on the screen but not in the data Claude
        // can fetch, so the question must not send it looking for one.
        assertFalse(asked.contains("win model"))
    }

    @Test
    fun `a road game is asked about as a road game`() {
        val asked = whatTheButtonAsks(
            game("2027-01-02", "Cincinnati", site = "away"), "Preview this game with Claude"
        )

        assertTrue(asked.contains("game at Cincinnati:"))
    }

    @Test
    fun `a date that has passed with no box score is still a preview`() {
        // The box score can be a day behind the floor. Branching on the date
        // would ask Claude to recap a game it holds no result for, and it
        // would answer from the fixture.
        val asked = whatTheButtonAsks(
            game("2020-01-01", "Baylor"), "Preview this game with Claude"
        )

        assertTrue(asked.startsWith("Give me a short scouting report"))
    }

    @Test
    fun `a game with no site named reads as a sentence anyway`() {
        // siteLabel gives nothing at all rather than a wrong "vs", and the
        // question is built so that leaves one space, not two.
        val asked = whatTheButtonAsks(
            game("2026-12-20", "Wichita State", 70, 65, site = null),
            "Recap this game with Claude"
        )

        assertTrue(asked.contains("game Wichita State:"))
        assertFalse(asked.contains("  "))
    }

    @Test
    fun `with nowhere to send a question there is no button`() {
        // The parameter is optional, so a caller that cannot open Ask shows no
        // button rather than one that does nothing.
        detail(game("2026-11-03", "Omaha", 78, 61), null)

        rule.onAllNodesWithText("Recap this game with Claude").assertCountEquals(0)
        rule.onAllNodesWithText("Preview this game with Claude").assertCountEquals(0)
    }
}
