package com.example

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.data.GameTeamStats
import com.example.data.JayhawksDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Room's schema is not exported (`exportSchema = false`), so there is no golden
 * schema to diff against — this is the only guard that an upgrade on a phone
 * that already holds data survives. A typo in the migration SQL, or a column
 * added to the entity but not to the migration, throws on first open after the
 * upgrade rather than at build time.
 *
 * The migration is driven directly against a hand-built v3 database instead of
 * through MigrationTestHelper, which expects an instrumented environment.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MigrationTest {

    private var helper: SupportSQLiteOpenHelper? = null

    @After
    fun tearDown() {
        helper?.close()
    }

    /** A v4 `games` table exactly as the first released APK created it. */
    private fun openV4(): SupportSQLiteDatabase {
        val db = openV3()
        JayhawksDatabase.migrations().single { it.startVersion == 3 }.migrate(db)
        return db
    }

    @Test
    fun `a v4 database from the shipped APK upgrades to v5`() {
        // v4 shipped in a release build before tipTime/tv/event existed. Adding
        // them to v4 rather than to a new version would leave every phone that
        // installed that build unable to open its own database.
        val db = openV4()
        db.execSQL(
            """INSERT INTO games (id, date, opponent, season, home, site, venue)
               VALUES (1, '2026-11-14', 'Nebraska', '2026-27', 0, 'neutral', 'Sanford Pentagon')"""
        )
        JayhawksDatabase.migrations().single { it.startVersion == 4 }.migrate(db)

        db.query("SELECT site, venue, tipTime, tv, event FROM games").use { c ->
            c.moveToFirst()
            // The v4 data survives, and the v5 columns arrive empty.
            assertEquals("neutral", c.getString(0))
            assertEquals("Sanford Pentagon", c.getString(1))
            assertTrue(c.isNull(2))
            assertTrue(c.isNull(3))
            assertTrue(c.isNull(4))
        }
        db.execSQL("UPDATE games SET tipTime = '15:30', tv = 'BTN+' WHERE id = 1")
        db.query("SELECT tipTime FROM games").use { c ->
            c.moveToFirst()
            assertEquals("15:30", c.getString(0))
        }
    }

    /** The v3 `games` table exactly as shipped, before site/venue/city existed. */
    private fun openV3(): SupportSQLiteDatabase {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getDatabasePath("migration-test.db").delete()
        val h = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name("migration-test.db")
                .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """CREATE TABLE games (
                                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                date TEXT NOT NULL, opponent TEXT NOT NULL,
                                season TEXT NOT NULL, home INTEGER,
                                teamScore INTEGER, opponentScore INTEGER,
                                periodScores TEXT)"""
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
                })
                .build()
        )
        helper = h
        return h.writableDatabase
    }

    @Test
    fun `v3 to v4 keeps rows and back-fills site from the old boolean`() {
        val db = openV3()
        db.execSQL(
            """INSERT INTO games (id, date, opponent, season, home, teamScore,
                 opponentScore, periodScores)
               VALUES (1, '2025-11-05', 'Kansas City', '2025-26', 1, 74, 64, '8-11'),
                      (2, '2025-11-15', 'Missouri', '2025-26', 0, 82, 77, NULL),
                      (3, '2026-11-03', 'Omaha', '2026-27', NULL, NULL, NULL, NULL)"""
        )

        JayhawksDatabase.migrations().single { it.startVersion == 3 }.migrate(db)

        db.query("SELECT id, site, venue, city FROM games ORDER BY id").use { c ->
            assertEquals(3, c.count)
            c.moveToFirst()
            // A phone that upgrades before its next sync still shows a site.
            assertEquals("home", c.getString(1))
            assertTrue("venue starts empty", c.isNull(2))
            c.moveToNext()
            assertEquals("away", c.getString(1))
            c.moveToNext()
            // Unknown stays unknown rather than being guessed as "away".
            assertTrue(c.isNull(1))
        }

        // The new table exists, and "to" really is quoted (it is a SQL keyword).
        db.execSQL(
            """INSERT INTO game_team_stats
                 (gameId, opponent, fgm, fga, tpm, tpa, ftm, fta, oreb, reb,
                  ast, "to", stl, blk, pf, pts)
               VALUES (1, 0, 28, 49, 9, 21, 9, 21, 6, 36, 20, 18, 6, 3, 19, 74)"""
        )
        db.query("SELECT reb, \"to\" FROM game_team_stats").use { c ->
            c.moveToFirst()
            assertEquals(36, c.getInt(0))
            assertEquals(18, c.getInt(1))
        }
    }

    @Test
    fun `a v3 database walks the whole chain to the current version`() {
        val db = openV3()
        db.execSQL(
            """INSERT INTO games (id, date, opponent, season, home)
               VALUES (1, '2025-11-05', 'Kansas City', '2025-26', 1)"""
        )
        // Every migration from 3 up, in order — the path a phone that has not
        // synced since the first release actually takes.
        for (m in JayhawksDatabase.migrations().filter { it.startVersion >= 3 }
            .sortedBy { it.startVersion }) {
            m.migrate(db)
        }
        db.query("SELECT site, tipTime FROM games").use { c ->
            c.moveToFirst()
            assertEquals("home", c.getString(0))
            assertTrue(c.isNull(1))
        }
    }

    @Test
    fun `the current entities round-trip through a real Room database`() = runTest {
        // Catches an entity/migration mismatch from the other direction: Room
        // builds the v4 schema itself here, so a column the migration adds but
        // the entity lacks (or vice versa) shows up as a failure to read back.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, JayhawksDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val dao = db.dao()
        val gameId = dao.insertGame(
            com.example.data.Game(
                date = "2026-03-04", opponent = "UCF", season = "2025-26",
                home = false, site = "neutral", venue = "T-Mobile Center",
                city = "Kansas City, MO", tipTime = "15:30", tv = "ESPN+",
                event = "Big 12 Tournament", teamScore = 70, opponentScore = 66
            )
        )
        dao.insertTeamStats(
            listOf(
                GameTeamStats(gameId = gameId, opponent = false, reb = 36, pts = 74, to = 18),
                GameTeamStats(gameId = gameId, opponent = true, reb = 29, pts = 64, to = 21)
            )
        )
        val game = dao.gamesOnce().single()
        assertEquals("neutral", game.site)
        assertEquals("neutral", game.siteOrLegacy)
        assertEquals("T-Mobile Center", game.venue)
        assertEquals("15:30", game.tipTime)
        assertEquals("ESPN+", game.tv)
        val rows = dao.teamStatsOnce()
        assertEquals(2, rows.size)
        assertEquals(36, rows.single { !it.opponent }.reb)
        assertEquals(21, rows.single { it.opponent }.to)

        // Deleting the game takes its team totals with it (CASCADE).
        dao.deleteGame(game)
        assertTrue(dao.teamStatsOnce().isEmpty())
        assertNull(dao.gamesOnce().firstOrNull())
        db.close()
    }
}
