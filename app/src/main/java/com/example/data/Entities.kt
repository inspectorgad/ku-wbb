package com.example.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "players")
data class Player(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val jerseyNumber: String = "",
    val position: String = "",
    // On the current roster. Maintained by the nightly roster scrape; former
    // players keep their stats but are shown in a separate roster section.
    val active: Boolean = true
)

// Dates are stored as ISO yyyy-MM-dd strings so lexicographic order matches
// chronological order without needing java.time (minSdk 24).
@Entity(tableName = "games")
data class Game(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,
    val opponent: String,
    // Basketball seasons span two years; labeled like "2025-26".
    val season: String,
    // Final score. Null until played.
    val teamScore: Int? = null,
    val opponentScore: Int? = null,
    // Per-quarter (and OT) points from KU's perspective, e.g. "8-11, 26-17, 17-16, 23-20"
    val periodScores: String? = null
)

@Entity(
    tableName = "stat_lines",
    foreignKeys = [
        ForeignKey(
            entity = Player::class,
            parentColumns = ["id"],
            childColumns = ["playerId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Game::class,
            parentColumns = ["id"],
            childColumns = ["gameId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("gameId"),
        Index(value = ["playerId", "gameId"], unique = true)
    ]
)
data class StatLine(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playerId: Long,
    val gameId: Long,
    val minutes: Int = 0,
    val fieldGoalsMade: Int = 0,
    val fieldGoalsAttempted: Int = 0,
    val threePointsMade: Int = 0,
    val threePointsAttempted: Int = 0,
    val freeThrowsMade: Int = 0,
    val freeThrowsAttempted: Int = 0,
    val offensiveRebounds: Int = 0,
    val totalRebounds: Int = 0,
    val assists: Int = 0,
    val turnovers: Int = 0,
    val steals: Int = 0,
    val blocks: Int = 0,
    val personalFouls: Int = 0,
    val points: Int = 0,
    val started: Boolean = false
)
