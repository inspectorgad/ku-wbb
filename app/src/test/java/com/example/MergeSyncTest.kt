package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.JayhawksDatabase
import com.example.data.Seeder
import com.example.data.StatLine
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MergeSyncTest {

    private lateinit var db: JayhawksDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, JayhawksDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun seedJson(): JSONObject = JSONObject(
        """
        {
          "players": [{"name": "Ada Alpha", "jerseyNumber": "1", "position": "G"}],
          "games": [
            {"date": "2025-11-05", "opponent": "Kansas City", "season": "2025-26",
             "home": true, "teamScore": 74, "opponentScore": 64,
             "periodScores": "8-11, 26-17, 17-16, 23-20",
             "lines": [{"player": "Ada Alpha", "min": 29, "fgm": 7, "fga": 15,
                        "tpm": 1, "tpa": 4, "ftm": 6, "fta": 6, "oreb": 1, "reb": 4,
                        "ast": 6, "to": 2, "stl": 1, "blk": 0, "pf": 2, "pts": 21,
                        "gs": 1}]}
          ]
        }
        """
    )

    @Test
    fun `merge into empty database inserts everything`() = runTest {
        Seeder.merge(seedJson(), db.dao())
        assertEquals(1, db.dao().playersOnce().size)
        val game = db.dao().gamesOnce().single()
        assertEquals(74, game.teamScore)
        assertEquals(64, game.opponentScore)
        assertEquals("8-11, 26-17, 17-16, 23-20", game.periodScores)
        val line = db.dao().statLinesOnce().single()
        assertEquals(21, line.points)
        assertEquals(15, line.fieldGoalsAttempted)
        assertEquals(6, line.assists)
        assertEquals(true, line.started)
    }

    @Test
    fun `home flag comes through and gap-fills a game that lacks it`() = runTest {
        val dao = db.dao()
        Seeder.merge(seedJson(), dao)
        assertEquals(true, dao.gamesOnce().single().home)

        // A game stored before the flag existed (or hand-entered) picks the
        // site up on the next sync, even though it already has a result.
        val other = com.example.data.Game(
            date = "2025-11-15", opponent = "Missouri", season = "2025-26",
            teamScore = 82, opponentScore = 77
        )
        dao.insertGame(other)
        Seeder.merge(
            JSONObject(
                """{"players":[],"games":[{"date":"2025-11-15","opponent":"Missouri",
                    "season":"2025-26","home":false,"teamScore":82,"opponentScore":77}]}"""
            ),
            dao
        )
        val missouri = dao.gamesOnce().single { it.opponent == "Missouri" }
        assertEquals(false, missouri.home)
        assertEquals(82, missouri.teamScore)
    }

    @Test
    fun `a seed without the home flag leaves an existing site alone`() = runTest {
        val dao = db.dao()
        Seeder.merge(seedJson(), dao)
        Seeder.merge(
            JSONObject(
                """{"players":[],"games":[{"date":"2025-11-05","opponent":"Kansas City",
                    "season":"2025-26"}]}"""
            ),
            dao
        )
        assertEquals(true, dao.gamesOnce().single().home)
    }

    @Test
    fun `merge is idempotent`() = runTest {
        Seeder.merge(seedJson(), db.dao())
        Seeder.merge(seedJson(), db.dao())
        assertEquals(1, db.dao().playersOnce().size)
        assertEquals(1, db.dao().gamesOnce().size)
        assertEquals(1, db.dao().statLinesOnce().size)
    }

    @Test
    fun `two games on the same date but different opponents both merge`() = runTest {
        val json = seedJson().apply {
            getJSONArray("games").put(
                JSONObject(
                    """{"date": "2025-11-05", "opponent": "Creighton", "season": "2025-26",
                        "teamScore": 80, "opponentScore": 60}"""
                )
            )
        }
        Seeder.merge(json, db.dao())
        assertEquals(2, db.dao().gamesOnce().size)
    }

    @Test
    fun `merge fills result of an existing resultless game but never changes an existing result`() =
        runTest {
            val dao = db.dao()
            dao.insertGame(
                com.example.data.Game(date = "2025-11-05", opponent = "Kansas City", season = "2025-26")
            )
            Seeder.merge(seedJson(), dao)
            assertEquals(74, dao.gamesOnce().single().teamScore)

            // A second merge with a different result must NOT overwrite.
            val altered = seedJson().apply {
                getJSONArray("games").getJSONObject(0).put("teamScore", 99)
            }
            Seeder.merge(altered, dao)
            assertEquals(74, dao.gamesOnce().single().teamScore)
        }

    @Test
    fun `merge never adds lines to a game that already has any`() = runTest {
        val dao = db.dao()
        Seeder.merge(seedJson(), dao)
        val game = dao.gamesOnce().single()
        val player = dao.playersOnce().single()
        // User records their own corrected line set: one line only.
        dao.statLinesOnce().forEach { dao.deleteStatLine(it) }
        dao.upsertStatLine(
            StatLine(playerId = player.id, gameId = game.id, minutes = 30, points = 25)
        )

        Seeder.merge(seedJson(), dao)
        val lines = dao.statLinesOnce()
        assertEquals(1, lines.size)
        assertEquals(25, lines.single().points)
    }

    @Test
    fun `merge refreshes roster facts on existing players without duplicating them`() = runTest {
        Seeder.merge(seedJson(), db.dao())
        // Next season: new number, new position, off the roster.
        val nextSeason = seedJson().apply {
            getJSONArray("players").getJSONObject(0)
                .put("jerseyNumber", "12")
                .put("position", "F")
                .put("active", false)
        }
        Seeder.merge(nextSeason, db.dao())
        val player = db.dao().playersOnce().single()
        assertEquals("12", player.jerseyNumber)
        assertEquals("F", player.position)
        assertEquals(false, player.active)
    }

    @Test
    fun `blank seed fields never erase existing roster facts`() = runTest {
        Seeder.merge(seedJson(), db.dao())
        val blanked = seedJson().apply {
            getJSONArray("players").getJSONObject(0)
                .put("jerseyNumber", "")
                .put("position", "")
        }
        Seeder.merge(blanked, db.dao())
        val player = db.dao().playersOnce().single()
        assertEquals("1", player.jerseyNumber)
        assertEquals("G", player.position)
    }

    @Test
    fun `player missing an active flag defaults to active and user-added players are untouched`() =
        runTest {
            val dao = db.dao()
            dao.insertPlayer(
                com.example.data.Player(name = "Hand Entered", jerseyNumber = "99", active = false)
            )
            Seeder.merge(seedJson(), dao)
            val byName = dao.playersOnce().associateBy { it.name }
            assertEquals(true, byName.getValue("Ada Alpha").active)
            assertEquals(false, byName.getValue("Hand Entered").active)
            assertEquals("99", byName.getValue("Hand Entered").jerseyNumber)
        }

    @Test
    fun `unknown player in lines is skipped without error`() = runTest {
        val json = seedJson().apply {
            getJSONArray("games").getJSONObject(0).getJSONArray("lines").getJSONObject(0)
                .put("player", "Nobody Known")
        }
        Seeder.merge(json, db.dao())
        assertEquals(0, db.dao().statLinesOnce().size)
        assertEquals(1, db.dao().gamesOnce().size)
    }
}
