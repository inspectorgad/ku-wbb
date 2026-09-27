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

    /**
     * Games are identified by date plus a normalized opponent name.
     *
     * The schedule page and the box score disagree on spelling — kuathletics
     * writes "South Dakota State" where the NCAA writes "South Dakota St." —
     * so keying on the raw name makes the fixture and its own result look like
     * two different games, permanently.
     */
    internal fun gameKey(date: String, opponent: String) = "$date|${normalizeTeam(opponent)}"

    internal fun normalizeTeam(name: String): String = name
        .replace(Regex("""\s*\(\d+\)\s*$"""), "")   // strip poll votes
        .lowercase()
        .replace(".", "")
        .replace(Regex("""\bstate\b"""), "st")
        .replace(Regex("""\buniversity\b"""), "")
        .replace(Regex("""\s+"""), " ")
        .trim()

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
            val seedHome = if (g.has("home")) g.getBoolean("home") else null
            val seedSite = g.optString("site").takeIf { it.isNotBlank() }
            val seedVenue = g.optString("venue").takeIf { it.isNotBlank() }
            val seedCity = g.optString("city").takeIf { it.isNotBlank() }
            val seedTip = g.optString("time").takeIf { it.isNotBlank() }
            val seedTv = g.optString("tv").takeIf { it.isNotBlank() }
            val seedEvent = g.optString("event").takeIf { it.isNotBlank() }

            val existing = gamesByKey[gameKey(date, opponent)]
            val gameId: Long
            if (existing == null) {
                gameId = dao.insertGame(
                    Game(
                        date = date,
                        opponent = opponent,
                        season = g.getString("season"),
                        home = seedHome,
                        site = seedSite,
                        venue = seedVenue,
                        city = seedCity,
                        tipTime = seedTip,
                        tv = seedTv,
                        event = seedEvent,
                        teamScore = seedTeamScore,
                        opponentScore = seedOppScore,
                        periodScores = seedPeriodScores
                    )
                )
            } else {
                gameId = existing.id
                val filledResult = existing.teamScore == null && existing.opponentScore == null &&
                    (seedTeamScore != null || seedOppScore != null)
                // The site is feed-owned, not gap-filled: a fixture is stored
                // from the schedule page, which cannot express a neutral site,
                // and only the box score's venue settles it. Gap-filling would
                // mean a correction never reached an already-synced phone.
                val updated = existing.copy(
                    home = seedHome ?: existing.home,
                    site = seedSite ?: existing.site,
                    venue = seedVenue ?: existing.venue,
                    city = seedCity ?: existing.city,
                    // Tip time is feed-owned and can move; a game that has been
                    // played drops it, and that is correct rather than a loss.
                    tipTime = if (seedTeamScore != null) null else seedTip ?: existing.tipTime,
                    tv = seedTv ?: existing.tv,
                    event = seedEvent ?: existing.event,
                    teamScore = if (filledResult) seedTeamScore else existing.teamScore,
                    opponentScore = if (filledResult) seedOppScore else existing.opponentScore,
                    periodScores = existing.periodScores
                        ?: (if (filledResult) seedPeriodScores else null)
                )
                if (updated != existing) dao.updateGame(updated)
            }

            // Team totals are replaced wholesale per game, and must be merged
            // BEFORE the stat-line early return below — otherwise they would
            // land only on a fresh install and never on a phone that already
            // has this game's player lines.
            val teamRows = mutableListOf<GameTeamStats>()
            g.optJSONObject("teamStats")?.let { teamRows += teamStats(gameId, false, it) }
            g.optJSONObject("opponentStats")?.let { teamRows += teamStats(gameId, true, it) }
            if (teamRows.isNotEmpty()) dao.insertTeamStats(teamRows)

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

        mergeStandings(root, dao)
    }

    private fun teamStats(gameId: Long, isOpponent: Boolean, o: JSONObject) = GameTeamStats(
        gameId = gameId,
        opponent = isOpponent,
        fgm = o.optInt("fgm"), fga = o.optInt("fga"),
        tpm = o.optInt("tpm"), tpa = o.optInt("tpa"),
        ftm = o.optInt("ftm"), fta = o.optInt("fta"),
        oreb = o.optInt("oreb"), reb = o.optInt("reb"),
        ast = o.optInt("ast"), to = o.optInt("to"),
        stl = o.optInt("stl"), blk = o.optInt("blk"),
        pf = o.optInt("pf"), pts = o.optInt("pts")
    )

    /**
     * Big 12 standings and poll snapshots are scraper-derived and change after
     * every result, so they are replaced per season rather than gap-filled —
     * the one deliberate exception to this file's never-overwrite rule, safe
     * because no field here is ever user-entered. Seeds that omit these keys
     * (older payloads) leave whatever is already stored untouched.
     */
    private suspend fun mergeStandings(root: JSONObject, dao: JayhawksDao) {
        root.optJSONArray("standings")?.let { arr ->
            val bySeason = mutableMapOf<String, MutableList<ConferenceStanding>>()
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                val season = s.optString("season").takeIf { it.isNotBlank() } ?: continue
                val seo = s.optString("seo").takeIf { it.isNotBlank() }
                    ?: s.optString("team").lowercase().replace(' ', '-')
                bySeason.getOrPut(season) { mutableListOf() }.add(
                    ConferenceStanding(
                        season = season,
                        seo = seo,
                        team = s.optString("team"),
                        confW = s.optInt("confW"),
                        confL = s.optInt("confL"),
                        overallW = s.optInt("overallW"),
                        overallL = s.optInt("overallL"),
                        nationalRank = s.optInt("nationalRank").takeIf { it > 0 },
                        netRank = s.optInt("netRank").takeIf { it > 0 }
                    )
                )
            }
            for ((season, rows) in bySeason) {
                dao.deleteStandingsForSeason(season)
                dao.insertStandings(rows)
            }
        }

        root.optJSONArray("polls")?.let { arr ->
            for (i in 0 until arr.length()) {
                val poll = arr.getJSONObject(i)
                val season = poll.optString("season").takeIf { it.isNotBlank() } ?: continue
                val name = poll.optString("name")
                val updated = poll.optString("updated")
                val rows = poll.optJSONArray("rows") ?: continue
                val entries = (0 until rows.length()).mapNotNull { j ->
                    val r = rows.getJSONObject(j)
                    val team = r.optString("team").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    PollEntry(
                        season = season,
                        team = team,
                        rank = r.optInt("rank"),
                        rankLabel = r.optString("rankLabel").ifBlank { r.optInt("rank").toString() },
                        record = r.optString("record"),
                        points = r.optString("points"),
                        previous = r.optString("previous"),
                        firstPlaceVotes = r.optInt("firstPlaceVotes"),
                        big12 = r.optBoolean("big12"),
                        pollName = name,
                        updated = updated
                    )
                }
                dao.deletePollForSeason(season)
                dao.insertPollEntries(entries)
            }
        }
    }
}
