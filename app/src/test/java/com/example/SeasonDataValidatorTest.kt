package com.example

import com.example.data.SeasonDataValidator
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SeasonDataValidatorTest {

    private fun payload(players: Int, games: Int, formatVersion: Int = 1): ByteArray {
        val root = JSONObject()
        root.put("formatVersion", formatVersion)
        root.put("players", JSONArray().apply {
            repeat(players) { put(JSONObject().put("name", "Player $it")) }
        })
        root.put("games", JSONArray().apply {
            repeat(games) {
                put(JSONObject().put("date", "2025-11-%02d".format(it + 1))
                    .put("opponent", "Opp").put("season", "2025-26"))
            }
        })
        return root.toString().toByteArray()
    }

    @Test
    fun `accepts a plausible payload`() {
        assertNotNull(SeasonDataValidator.parse(payload(players = 12, games = 30)))
    }

    @Test
    fun `rejects payload with too few players or games`() {
        assertNull(SeasonDataValidator.parse(payload(players = 2, games = 30)))
        assertNull(SeasonDataValidator.parse(payload(players = 12, games = 3)))
    }

    @Test
    fun `rejects future format versions`() {
        assertNull(SeasonDataValidator.parse(payload(players = 12, games = 30, formatVersion = 2)))
    }

    @Test
    fun `rejects truncated and garbage payloads`() {
        val valid = payload(players = 12, games = 30)
        assertNull(SeasonDataValidator.parse(valid.copyOf(valid.size / 2)))
        assertNull(SeasonDataValidator.parse("not json at all".toByteArray()))
        assertNull(SeasonDataValidator.parse(ByteArray(0)))
    }
}
