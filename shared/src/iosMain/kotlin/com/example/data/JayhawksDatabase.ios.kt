package com.example.data

import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

// The iPhone app's own migrations, which are not the Android ones: those run
// on SupportSQLiteDatabase, an Android-only type, and describe upgrades from
// versions that never existed on iPhone. iOS shipped at version 9, so 9 -> 10
// is the first upgrade any iPhone can be asked to make, and every later one
// belongs here too.
//
// This file previously said an iPhone install was always new and so needed no
// migrations. That held only while the version never moved. It has now moved,
// and a TestFlight update keeps the database file in Documents, so without
// this the app would find version 9 on disk against 10 in the code, fail to
// open, and crash on launch — every launch — for anyone already carrying the
// earlier build.
private val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE games ADD COLUMN winProbability REAL")
        connection.execSQL("ALTER TABLE games ADD COLUMN ratingSource TEXT")
    }
}

/**
 * Opens the database on iPhone: a file in the app's Documents folder, read and
 * written through the SQLite that Room ships (the same engine and version on
 * every iPhone), with queries run off the main thread.
 */
fun createDatabase(): JayhawksDatabase {
    val documents = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).first() as String
    return Room.databaseBuilder<JayhawksDatabase>(name = "$documents/ku_wbb.db")
        .addMigrations(MIGRATION_9_10)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
}
