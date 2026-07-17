package com.example.data

import android.content.Context
import org.json.JSONObject

/**
 * Syncs the bundled assets/seed.json into the database on every launch,
 * gap-filling only — it never overwrites user-entered data:
 * - players are added if their name isn't already present
 * - games are added if no game exists for that date + opponent
 * - an existing game gets seed results only if it has none
 * - an existing game gets seed stat lines only if it has none
 *
 * This lets an updated APK (with fresh season data baked in) install over the
 * old one and pick up the new games while keeping local edits intact.
 *
 * Seed game shape:
 * {
 *   "date": "2025-11-05", "opponent": "Kansas City", "season": "2025-26",
 *   "teamScore": 74, "opponentScore": 64,
 *   "periodScores": "8-11, 26-17, 17-16, 23-20",
 *   "lines": [{"player": "<player name>", "min": 29, "fgm": 7, "fga": 15,
 *              "tpm": 1, "tpa": 4, "ftm": 6, "fta": 6, "oreb": 1, "reb": 4,
 *              "ast": 6, "to": 2, "stl": 1, "blk": 0, "pf": 2, "pts": 21,
 *              "gs": 1}]
 * }
 */
object Seeder {

    suspend fun sync(context: Context, dao: JayhawksDao) {
        val json = runCatching {
            context.assets.open("seed.json").bufferedReader().use { it.readText() }
        }.getOrNull() ?: return

        runCatching { merge(JSONObject(json), dao) }
    }

    private fun gameKey(date: String, opponent: String) = "$date|${opponent.lowercase()}"

    /** Also used by [SeasonSync] for network-fetched season data. */
    suspend fun merge(root: JSONObject, dao: JayhawksDao) {
        val existingByName = dao.playersOnce().associateBy { it.name }
        val playerIdsByName = existingByName.mapValues { it.value.id }.toMutableMap()

        val players = root.optJSONArray("players")
        if (players != null) {
            for (i in 0 until players.length()) {
                val p = players.getJSONObject(i)
                val name = p.getString("name")
                val jersey = p.optString("jerseyNumber", "")
                val position = p.optString("position", "")
                val active = p.optBoolean("active", true)
                val existing = existingByName[name]
                if (existing == null) {
                    playerIdsByName[name] = dao.insertPlayer(
                        Player(
                            name = name,
                            jerseyNumber = jersey,
                            position = position,
                            active = active
                        )
                    )
                } else {
                    // Roster facts (number, position, current-roster status) are
                    // scraper-owned and refreshed on every sync; blank seed values
                    // never erase what's already there.
                    val updated = existing.copy(
                        jerseyNumber = jersey.ifBlank { existing.jerseyNumber },
                        position = position.ifBlank { existing.position },
                        active = active
                    )
                    if (updated != existing) dao.updatePlayer(updated)
                }
            }
        }

        // Tournament weekends can put two games on nearby dates, so games
        // are keyed by date + opponent rather than date alone.
        val gamesByKey = dao.gamesOnce().associateBy { gameKey(it.date, it.opponent) }
        val gamesWithLines = dao.statLinesOnce().map { it.gameId }.toSet()

        val games = root.optJSONArray("games") ?: return
        for (i in 0 until games.length()) {
            val g = games.getJSONObject(i)
            val date = g.getString("date")
            val opponent = g.getString("opponent")
            val seedTeamScore = if (g.has("teamScore")) g.getInt("teamScore") else null
            val seedOppScore = if (g.has("opponentScore")) g.getInt("opponentScore") else null
            val seedPeriodScores = g.optString("periodScores").takeIf { it.isNotBlank() }

            val existing = gamesByKey[gameKey(date, opponent)]
            val gameId: Long
            if (existing == null) {
                gameId = dao.insertGame(
                    Game(
                        date = date,
                        opponent = opponent,
                        season = g.getString("season"),
                        teamScore = seedTeamScore,
                        opponentScore = seedOppScore,
                        periodScores = seedPeriodScores
                    )
                )
            } else {
                gameId = existing.id
                if (existing.teamScore == null && existing.opponentScore == null &&
                    (seedTeamScore != null || seedOppScore != null)
                ) {
                    dao.updateGame(
                        existing.copy(
                            teamScore = seedTeamScore,
                            opponentScore = seedOppScore,
                            periodScores = existing.periodScores ?: seedPeriodScores
                        )
                    )
                }
            }

            if (existing != null && gameId in gamesWithLines) continue
            val lines = g.optJSONArray("lines") ?: continue
            for (j in 0 until lines.length()) {
                val l = lines.getJSONObject(j)
                val playerId = playerIdsByName[l.getString("player")] ?: continue
                dao.upsertStatLine(
                    StatLine(
                        playerId = playerId,
                        gameId = gameId,
                        minutes = l.optInt("min"),
                        fieldGoalsMade = l.optInt("fgm"),
                        fieldGoalsAttempted = l.optInt("fga"),
                        threePointsMade = l.optInt("tpm"),
                        threePointsAttempted = l.optInt("tpa"),
                        freeThrowsMade = l.optInt("ftm"),
                        freeThrowsAttempted = l.optInt("fta"),
                        offensiveRebounds = l.optInt("oreb"),
                        totalRebounds = l.optInt("reb"),
                        assists = l.optInt("ast"),
                        turnovers = l.optInt("to"),
                        steals = l.optInt("stl"),
                        blocks = l.optInt("blk"),
                        personalFouls = l.optInt("pf"),
                        points = l.optInt("pts"),
                        started = l.optInt("gs") == 1
                    )
                )
            }
        }
    }
}
