package com.example

import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.data.Game
import com.example.platform.Device
import com.example.platform.Settings
import com.example.platform.SettingsEditor
import com.example.ui.AskScreen
import com.example.ui.GameDetailScreen
import com.example.ui.LocalDevice
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

    @Test
    fun `a handed-over question is taken once, and says why it is waiting`() {
        // The question survives in saved state, so a rotation or a return from
        // a killed process rebuilds this screen with it still in hand. Taking
        // it exactly once is what stops that rebuild asking it again on the
        // reader's account; this pins the callback that does the taking.
        var taken = 0
        val q = previewQuestion("2026-11-14", "vs ", "Nebraska")
        rule.setContent {
            CompositionLocalProvider(LocalDevice provides FakeDevice()) {
                MyApplicationTheme {
                    Surface {
                        AskScreen(onBack = {}, prefill = q, onPrefillTaken = { taken++ })
                    }
                }
            }
        }
        rule.waitForIdle()

        assertEquals(1, taken)
        // No key saved, so it waits in the box and says so rather than
        // looking like an ordinary question that simply did not go.
        rule.onNodeWithText(q).assertIsDisplayed()
        rule.onNodeWithText("Add your Anthropic API key above, then press Ask.").assertIsDisplayed()
    }

    @Test
    fun `with nothing handed over nothing is taken`() {
        var taken = 0
        rule.setContent {
            CompositionLocalProvider(LocalDevice provides FakeDevice()) {
                MyApplicationTheme {
                    Surface {
                        AskScreen(onBack = {}, onPrefillTaken = { taken++ })
                    }
                }
            }
        }
        rule.waitForIdle()

        assertEquals(0, taken)
    }
}

/** A phone that remembers nothing and has no key saved. */
private class FakeDevice : Device {
    private val groups = mutableMapOf<String, MutableMap<String, Any>>()

    override fun settings(name: String): Settings {
        val values = groups.getOrPut(name) { mutableMapOf() }
        return object : Settings {
            override fun string(key: String): String? = values[key] as? String
            override fun long(key: String, default: Long): Long = values[key] as? Long ?: default
            override fun int(key: String, default: Int): Int = values[key] as? Int ?: default
            override fun boolean(key: String, default: Boolean): Boolean =
                values[key] as? Boolean ?: default

            override fun edit(changes: SettingsEditor.() -> Unit) {
                val editor = object : SettingsEditor {
                    override fun putString(key: String, value: String?) {
                        if (value == null) values.remove(key) else values[key] = value
                    }
                    override fun putLong(key: String, value: Long) { values[key] = value }
                    override fun putInt(key: String, value: Int) { values[key] = value }
                    override fun putBoolean(key: String, value: Boolean) { values[key] = value }
                    override fun remove(key: String) { values.remove(key) }
                }
                editor.changes()
            }
        }
    }

    override fun readFile(name: String): String? = null
    override fun writeFile(name: String, text: String): Boolean = false
    override fun readAsset(name: String): String? = null
    override fun loadSecret(name: String): String? = null
    override fun saveSecret(name: String, value: String): Boolean = false
    override fun forgetSecret(name: String) {}
}
