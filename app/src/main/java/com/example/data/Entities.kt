package com.example.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A Big 12 team's record for one season, computed by the scraper from the NCAA
 * scoreboard sweep. Unlike players/games this is *derived* data with no
 * user-entered fields, so sync replaces it wholesale rather than gap-filling.
 */
@Entity(tableName = "standings", primaryKeys = ["season", "seo"])
data class ConferenceStanding(
    val season: String,
    val seo: String,
    val team: String,
    val confW: Int = 0,
    val confL: Int = 0,
    val overallW: Int = 0,
    val overallL: Int = 0,
    // AP national rank as of the season's latest poll snapshot.
    val nationalRank: Int? = null,
    val netRank: Int? = null
) {
    val confPct: Double get() = (confW + confL).let { if (it == 0) 0.0 else confW.toDouble() / it }
    val overallPct: Double get() = (overallW + overallL).let { if (it == 0) 0.0 else overallW.toDouble() / it }
}

/**
 * One row of a national poll snapshot (AP top 25). The endpoint only serves
 * the current poll, so each season keeps the latest capture — which at
 * season's end is that season's final poll.
 */
@Entity(tableName = "poll_entries", primaryKeys = ["season", "team"])
data class PollEntry(
    val season: String,
    val team: String,
    val rank: Int,
    // Preserves ties as published, e.g. "T-22".
    val rankLabel: String,
    val record: String = "",
    val points: String = "",
    val previous: String = "",
    val firstPlaceVotes: Int = 0,
    val big12: Boolean = false,
    val pollName: String = "",
    val updated: String = ""
)

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
    // Site: true = KU hosts, false = on the road. Null when unknown (seeds
    // predating this field, or a hand-entered game) so the UI can stay silent
    // rather than claim a site it doesn't know.
    val home: Boolean? = null,
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
