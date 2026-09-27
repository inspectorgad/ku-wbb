package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Player::class, Game::class, StatLine::class,
        ConferenceStanding::class, PollEntry::class, GameTeamStats::class],
    version = 5,
    exportSchema = false
)
abstract class JayhawksDatabase : RoomDatabase() {
    abstract fun dao(): JayhawksDao

    companion object {
        @Volatile
        private var instance: JayhawksDatabase? = null

        // v1 -> v2: Big 12 standings and national poll snapshots.
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS standings (
                        season TEXT NOT NULL, seo TEXT NOT NULL, team TEXT NOT NULL,
                        confW INTEGER NOT NULL, confL INTEGER NOT NULL,
                        overallW INTEGER NOT NULL, overallL INTEGER NOT NULL,
                        nationalRank INTEGER, netRank INTEGER,
                        PRIMARY KEY(season, seo))"""
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS poll_entries (
                        season TEXT NOT NULL, team TEXT NOT NULL, rank INTEGER NOT NULL,
                        rankLabel TEXT NOT NULL, record TEXT NOT NULL, points TEXT NOT NULL,
                        previous TEXT NOT NULL, firstPlaceVotes INTEGER NOT NULL,
                        big12 INTEGER NOT NULL, pollName TEXT NOT NULL, updated TEXT NOT NULL,
                        PRIMARY KEY(season, team))"""
                )
            }
        }

        // v2 -> v3: games gained the home/away flag (nullable: unknown for
        // rows that predate it, which the UI renders as no site label).
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE games ADD COLUMN home INTEGER")
            }
        }

        // v3 -> v4: three-state site (home/away/neutral) plus venue, and
        // official per-game team totals for both sides.
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE games ADD COLUMN site TEXT")
                db.execSQL("ALTER TABLE games ADD COLUMN venue TEXT")
                db.execSQL("ALTER TABLE games ADD COLUMN city TEXT")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS game_team_stats (
                        gameId INTEGER NOT NULL, opponent INTEGER NOT NULL,
                        fgm INTEGER NOT NULL, fga INTEGER NOT NULL,
                        tpm INTEGER NOT NULL, tpa INTEGER NOT NULL,
                        ftm INTEGER NOT NULL, fta INTEGER NOT NULL,
                        oreb INTEGER NOT NULL, reb INTEGER NOT NULL,
                        ast INTEGER NOT NULL, "to" INTEGER NOT NULL,
                        stl INTEGER NOT NULL, blk INTEGER NOT NULL,
                        pf INTEGER NOT NULL, pts INTEGER NOT NULL,
                        PRIMARY KEY(gameId, opponent),
                        FOREIGN KEY(gameId) REFERENCES games(id) ON DELETE CASCADE)"""
                )
                // Back-fill the new column from the old boolean so a phone that
                // upgrades before its next sync still shows a site.
                db.execSQL(
                    "UPDATE games SET site = CASE home WHEN 1 THEN 'home' WHEN 0 THEN 'away' END"
                )
            }
        }

        // v4 -> v5: schedule detail — tip time, broadcaster, and the event a
        // game belongs to.
        //
        // These were very nearly folded into MIGRATION_3_4 instead. They could
        // not be: v4 had already shipped in a release APK without them, so
        // redefining v4 would have left anyone who installed that build with a
        // database Room validates against a newer v4 and refuses to open —
        // a crash on launch, not a missed column. A version that has shipped
        // is frozen; new columns get a new version.
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE games ADD COLUMN tipTime TEXT")
                db.execSQL("ALTER TABLE games ADD COLUMN tv TEXT")
                db.execSQL("ALTER TABLE games ADD COLUMN event TEXT")
            }
        }

        /** Every migration, in order. Exposed so tests exercise the real set. */
        fun migrations(): Array<Migration> =
            arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)

        fun get(context: Context): JayhawksDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    JayhawksDatabase::class.java,
                    "ku_wbb.db"
                ).addMigrations(*migrations())
                    .build().also { instance = it }
            }
    }
}
