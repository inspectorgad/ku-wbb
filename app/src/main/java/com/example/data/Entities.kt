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
    // Bio, from kuathletics — the NCAA feed carries none of it. A former
    // player keeps whatever was last known rather than losing it.
    val height: String? = null,
    val academicYear: String? = null,
    val hometown: String? = null,
    // The schools before this one, most recent last: "Andover Central HS /
    // Creighton". A transfer path, not a single school.
    val lastSchool: String? = null,
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
    // rather than claim a site it doesn't know. Kept for rows written before
    // `site` existed; `siteOrLegacy` is what the UI should read.
    val home: Boolean? = null,
    // "home" | "away" | "neutral", derived by the scraper from the venue. The
    // feed's own isHome only marks the nominal home side, which at a neutral
    // site is whoever was seeded higher, so it cannot be trusted for this.
    val site: String? = null,
    val venue: String? = null,
    val city: String? = null,
    // Tip-off local to the venue, "18:30". Null until the conference sets the
    // TV windows, which for most Big 12 games is well into the season.
    val tipTime: String? = null,
    // Broadcaster, when the schedule names one — it usually does not.
    val tv: String? = null,
    // Tournament or showcase this game belongs to, e.g. "Cancun Challenge".
    val event: String? = null,
    // Counts toward the Big 12 record: a regular-season game against a member,
    // before the conference tournament. All three conditions matter — without
    // them the 18-game, 8-10 conference record reads as 21 games and 9-12.
    val conference: Boolean = false,
    // Overtime periods played, null in regulation.
    val overtime: Int? = null,
    // The opponent is not Division I, so the NCAA's own record and site splits
    // exclude this game. KU's 22-14 is 21-14 to the NET because of one of these.
    val nonD1: Boolean = false,
    // What the opponent brought into the game — their national rank if they
    // had one, their tournament seed, and their record to that point. A final
    // score alone never says who the other team was at the time.
    val opponentRank: Int? = null,
    val opponentSeed: Int? = null,
    val opponentRecord: String? = null,
    // Final score. Null until played.
    val teamScore: Int? = null,
    val opponentScore: Int? = null,
    // Per-quarter (and OT) points from KU's perspective, e.g. "8-11, 26-17, 17-16, 23-20"
    val periodScores: String? = null
) {
    /** Site for display, falling back to the older boolean on legacy rows. */
    val siteOrLegacy: String?
        get() = site ?: when (home) {
            true -> "home"
            false -> "away"
            null -> null
        }
}

/**
 * A single opponent player's line in one game.
 *
 * Deliberately not stored through the player table: these are not KU players
 * and must never reach the roster. Scraper-owned, so a sync replaces a game's
 * rows outright.
 */
@Entity(
    tableName = "opponent_stat_lines",
    primaryKeys = ["gameId", "playerName"],
    foreignKeys = [
        ForeignKey(
            entity = Game::class,
            parentColumns = ["id"],
            childColumns = ["gameId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class OpponentStatLine(
    val gameId: Long,
    val playerName: String,
    val jerseyNumber: String = "",
    val position: String = "",
    // From the opposing school's own roster page, matched on the name. A box
    // score has no field for it, so this is empty whenever that team's roster
    // was not reachable — which the UI reads as "not known" and says nothing.
    val height: String = "",
    val minutes: Int = 0,
    val fieldGoalsMade: Int = 0,
    val fieldGoalsAttempted: Int = 0,
    val threePointsMade: Int = 0,
    val threePointsAttempted: Int = 0,
    val freeThrowsMade: Int = 0,
    val freeThrowsAttempted: Int = 0,
    val offensiveRebounds: Int = 0,
    val rebounds: Int = 0,
    val assists: Int = 0,
    val turnovers: Int = 0,
    val steals: Int = 0,
    val blocks: Int = 0,
    val fouls: Int = 0,
    val points: Int = 0,
    val started: Boolean = false
)

/**
 * Official team totals for one side of one game, straight from the box score.
 *
 * These are deliberately NOT the sum of the player lines: team rebounds
 * (deadballs) and team turnovers belong to no individual, so summing the box
 * understates them — KU's 2025-26 rebounding is 35.0/game officially against
 * 30.8 summed. Team-level figures must come from here.
 */
@Entity(
    tableName = "game_team_stats",
    primaryKeys = ["gameId", "opponent"],
    foreignKeys = [
        ForeignKey(
            entity = Game::class,
            parentColumns = ["id"],
            childColumns = ["gameId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class GameTeamStats(
    val gameId: Long,
    // false = Kansas' totals, true = the opponent's.
    val opponent: Boolean,
    val fgm: Int = 0,
    val fga: Int = 0,
    val tpm: Int = 0,
    val tpa: Int = 0,
    val ftm: Int = 0,
    val fta: Int = 0,
    val oreb: Int = 0,
    val reb: Int = 0,
    val ast: Int = 0,
    val to: Int = 0,
    val stl: Int = 0,
    val blk: Int = 0,
    val pf: Int = 0,
    val pts: Int = 0
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
