package com.example.data

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor

/**
 * The schema is exported and committed, and CI fails when a build regenerates
 * one that differs.
 *
 * It was not exported for eight versions, and the migration tests were the
 * only thing standing behind an upgrade on a phone that already held data.
 * That was tolerable while the only thing that could change the schema was a
 * migration written here on purpose. It stopped being tolerable with the move
 * to a shared Android/iPhone module: the Room version, the KSP version and the
 * code generator itself all changed, and all three feed the schema hash Room
 * checks when it opens the database. A hash that comes out different is a
 * crash on launch for everyone upgrading, not a warning.
 *
 * So the schema as it stood at v9 on the toolchain that built the installed
 * app was recorded first, and is diffed after every move since. The hash to
 * watch is 2c20466365b2a25ef4beb786cf1e9928. See PORT-PLAN.md, phase 0.
 */
@Database(
    entities = [Player::class, Game::class, StatLine::class,
        ConferenceStanding::class, PollEntry::class, GameTeamStats::class,
        OpponentStatLine::class, OpponentRosterPlayer::class],
    version = 9,
    exportSchema = true
)
@ConstructedBy(JayhawksDatabaseConstructor::class)
abstract class JayhawksDatabase : RoomDatabase() {
    abstract fun dao(): JayhawksDao

    // Each phone opens the database its own way: JayhawksDatabase.get(context)
    // on Android (androidMain), createDatabase() on iPhone (iosMain). Neither
    // belongs here, because neither compiles on the other platform.
    companion object
}

/** Builds the database class on every platform; Room's compiler writes each platform's version. */
@Suppress("KotlinNoActualForExpect")
expect object JayhawksDatabaseConstructor : RoomDatabaseConstructor<JayhawksDatabase> {
    override fun initialize(): JayhawksDatabase
}
