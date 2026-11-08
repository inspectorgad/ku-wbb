package com.example.data

import com.example.json.JSONObject
import com.example.platform.Device
import kotlin.math.pow

/**
 * The season simulator: plays the rest of the season out many times and counts
 * what happened.
 *
 * A mirror of docs/sim.js, and deliberately a close one. Both use the same
 * small generator (mulberry32) from the same starting seed and draw in the
 * same order, so the phone and the dashboard show the same numbers for the
 * same season, and a what-if toggled on one gives what it gives on the other.
 * SeasonSimulatorTest.kt pins the same fixture docs/sim.test.mjs pins; if the
 * two ever disagree, one of them has drifted and the pair is the bug.
 *
 * Common code: no java.* here, because this compiles for iPhone too.
 */
object SeasonSimulator {

    const val RUNS = 20000
    private const val SEED = 20271

    /**
     * JavaScript's mulberry32, bit for bit.
     *
     * The translation is where this is most likely to go wrong, so it is
     * written out rather than paraphrased. JS does its arithmetic on 32-bit
     * ints here: `| 0` and `Math.imul` are what Kotlin's Int already does,
     * `>>>` is `ushr`, and `>>> 0` reinterprets the result as unsigned, which
     * is the `and 0xFFFFFFFF` on a Long below. Drop that last step and every
     * other draw comes out negative.
     */
    private class Mulberry32(private var a: Int) {
        fun next(): Double {
            a += 0x6D2B79F5
            var t = (a xor (a ushr 15)) * (1 or a)
            t = (t + ((t xor (t ushr 7)) * (61 or t))) xor t
            return ((t xor (t ushr 14)).toLong() and 0xFFFFFFFFL) / 4294967296.0
        }
    }

    data class Game(
        val date: String,
        val opponent: String,
        val site: String,
        val conference: Boolean,
        val key: String,
        val p: Double,
    )

    data class Other(
        val team: String,
        val key: String,
        val confW: Int,
        val remaining: Int,
        val pVsAverage: Double,
        val rating: Double,
    )

    data class Kansas(
        val confW: Int,
        val confL: Int,
        val overallW: Int,
        val overallL: Int,
        val rating: Double,
    )

    data class Inputs(
        val season: String,
        val scale: Double,
        val kansas: Kansas,
        val remaining: List<Game>,
        val others: List<Other>,
        val unrated: List<String>,
    )

    data class Result(
        val runs: Int,
        val games: Int,
        val expectedWins: Double,
        val expectedLosses: Double,
        val winsLow: Int,
        val winsMid: Int,
        val winsHigh: Int,
        val title: Double,
        val shareOfTitle: Double,
        val top4: Double,
        val top8: Double,
        val averagePlace: Double,
        val seedMid: Int,
        val seedLow: Int,
        val seedHigh: Int,
        val autoBid: Double,
    )

    /** seed["simulation"], or null when it is absent or unusable. */
    fun parse(root: JSONObject): Inputs? {
        val ku = root.optJSONObject("kansas") ?: return null
        val rem = root.optJSONArray("remaining") ?: return null
        val oth = root.optJSONArray("others") ?: return null
        if (rem.length() == 0 || oth.length() == 0) return null

        val games = ArrayList<Game>(rem.length())
        for (i in 0 until rem.length()) {
            val g = rem.optJSONObject(i) ?: continue
            games.add(
                Game(
                    date = g.optString("date"),
                    opponent = g.optString("opponent"),
                    site = g.optString("site").ifBlank { "neutral" },
                    conference = g.optBoolean("conference", false),
                    key = g.optString("key"),
                    p = g.optDouble("p", 0.5),
                )
            )
        }
        val others = ArrayList<Other>(oth.length())
        for (i in 0 until oth.length()) {
            val o = oth.optJSONObject(i) ?: continue
            others.add(
                Other(
                    team = o.optString("team"),
                    key = o.optString("key"),
                    confW = o.optInt("confW", 0),
                    remaining = o.optInt("remaining", 0),
                    pVsAverage = o.optDouble("pVsAverage", 0.5),
                    rating = o.optDouble("rating", 0.0),
                )
            )
        }
        if (games.isEmpty() || others.isEmpty()) return null

        val unrated = root.optJSONArray("unrated")
        return Inputs(
            season = root.optString("season"),
            scale = root.optDouble("scale", 1.0).let { if (it.isNaN() || it == 0.0) 1.0 else it },
            kansas = Kansas(
                confW = ku.optInt("confW", 0),
                confL = ku.optInt("confL", 0),
                overallW = ku.optInt("overallW", 0),
                overallL = ku.optInt("overallL", 0),
                rating = ku.optDouble("rating", 0.0),
            ),
            remaining = games,
            others = others,
            unrated = buildList {
                if (unrated != null) for (i in 0 until unrated.length()) {
                    (unrated.opt(i) as? String)?.let { add(it) }
                }
            },
        )
    }

    /** One team in a simulated table: its wins, its tiebreak draw, its rating. */
    private class Slot(var w: Int, var tie: Double, var rating: Double, var ku: Boolean)

    /**
     * forced: game index -> "W" or "L" for results the reader has decided.
     */
    fun simulate(inputs: Inputs, forced: Map<Int, String> = emptyMap(), runs: Int = RUNS): Result {
        val rnd = Mulberry32(SEED)
        val ku = inputs.kansas
        val rem = inputs.remaining
        val others = inputs.others
        val scale = inputs.scale
        val index = HashMap<String, Int>(others.size)
        others.forEachIndexed { i, o -> index[o.key] = i }
        val n = others.size + 1

        // A game between two rated teams on a neutral floor, which is what the
        // conference tournament is played on.
        fun pWin(a: Double, b: Double): Double =
            1.0 / (1.0 + pow10(-(a - b) / scale))

        val overall = IntArray(rem.size + 1)
        var titles = 0
        var shares = 0
        var top4 = 0
        var top8 = 0
        var autoBid = 0
        var placeSum = 0L
        val seeds = IntArray(runs)
        val confWins = IntArray(others.size)
        val order = ArrayList<Slot>(n)
        repeat(n) { order.add(Slot(0, 0.0, 0.0, false)) }
        val bracket = arrayOfNulls<Slot>(n + 1)

        for (run in 0 until runs) {
            for (i in others.indices) confWins[i] = others[i].confW
            var wins = 0
            var kuConf = ku.confW

            // The draw happens whether or not this one is forced, so pinning a
            // result does not shift the random numbers the rest of them get.
            for (m in rem.indices) {
                val draw = rnd.next()
                val f = forced[m]
                val won = when (f) {
                    "W" -> true
                    "L" -> false
                    else -> draw < rem[m].p
                }
                if (won) {
                    wins++
                    if (rem[m].conference) kuConf++
                } else if (rem[m].conference) {
                    index[rem[m].key]?.let { confWins[it]++ }
                }
            }
            for (i in others.indices) {
                val o = others[i]
                repeat(o.remaining) { if (rnd.next() < o.pVsAverage) confWins[i]++ }
            }

            // Every team gets a tiebreak draw, Kansas included: the Big 12's
            // real tiebreakers begin with head-to-head and this simulation does
            // not keep who beat whom.
            for (i in others.indices) {
                val s = order[i]
                s.w = confWins[i]; s.tie = rnd.next(); s.rating = others[i].rating; s.ku = false
            }
            order[others.size].let {
                it.w = kuConf; it.tie = rnd.next(); it.rating = ku.rating; it.ku = true
            }
            order.sortWith(compareByDescending<Slot> { it.w }.thenByDescending { it.tie })

            var place = 0
            var ahead = 0
            for (i in 0 until n) {
                val s = order[i]
                bracket[i + 1] = s
                if (s.ku) place = i + 1 else if (s.w > kuConf) ahead++
            }

            val champion = playBracket(bracket, n, ::pWin, rnd)

            overall[wins]++
            placeSum += place
            if (place == 1) titles++
            if (ahead == 0) shares++
            if (place <= 4) top4++
            if (place <= 8) top8++
            if (champion != null && champion.ku) autoBid++
            seeds[run] = place
        }

        seeds.sort()
        fun seedAt(q: Double): Int = seeds[minOf(seeds.size - 1, (q * seeds.size).toInt())]
        fun winsAt(q: Double): Int {
            var seen = 0
            for (k in overall.indices) {
                seen += overall[k]
                if (seen > q * runs) return k
            }
            return overall.size - 1
        }
        var weighted = 0.0
        for (k in overall.indices) weighted += overall[k].toDouble() * k
        val exp = weighted / runs

        return Result(
            runs = runs,
            games = ku.overallW + ku.overallL + rem.size,
            expectedWins = ku.overallW + exp,
            expectedLosses = ku.overallL + rem.size - exp,
            winsLow = ku.overallW + winsAt(0.1),
            winsMid = ku.overallW + winsAt(0.5),
            winsHigh = ku.overallW + winsAt(0.9),
            title = titles.toDouble() / runs,
            shareOfTitle = shares.toDouble() / runs,
            top4 = top4.toDouble() / runs,
            top8 = top8.toDouble() / runs,
            averagePlace = placeSum.toDouble() / runs,
            seedMid = seedAt(0.5),
            seedLow = seedAt(0.1),
            seedHigh = seedAt(0.9),
            autoBid = autoBid.toDouble() / runs,
        )
    }

    /**
     * One Big 12 tournament. [bracket] is 1-based by seed; returns the winner.
     *
     * Seeds 9-16 play the first round, 5-8 come in for the second and 1-4 for
     * the quarter-finals, so the top seed needs four wins and the bottom eight
     * need five. 1 meets 4's side in a semi-final and 2 meets 3's, so the top
     * two seeds can only meet in the final.
     */
    private fun playBracket(
        bracket: Array<Slot?>,
        n: Int,
        pWin: (Double, Double) -> Double,
        rnd: Mulberry32,
    ): Slot? {
        fun play(a: Slot?, b: Slot?): Slot? {
            if (a == null) return b
            if (b == null) return a
            return if (rnd.next() < pWin(a.rating, b.rating)) a else b
        }
        if (n < 16) {
            // Not the 16-team Big 12 shape. Play whoever is there off in seed
            // order rather than inventing byes.
            var field = (1..n).map { bracket[it] }
            while (field.size > 1) {
                val next = ArrayList<Slot?>((field.size + 1) / 2)
                var i = 0
                while (i < field.size) {
                    next.add(if (i + 1 < field.size) play(field[i], field[i + 1]) else field[i])
                    i += 2
                }
                field = next
            }
            return field.firstOrNull()
        }
        val r1 = arrayOf(
            play(bracket[9], bracket[16]), play(bracket[10], bracket[15]),
            play(bracket[11], bracket[14]), play(bracket[12], bracket[13]),
        )
        val r2 = arrayOf(
            play(bracket[8], r1[0]), play(bracket[7], r1[1]),
            play(bracket[6], r1[2]), play(bracket[5], r1[3]),
        )
        val qf = arrayOf(
            play(bracket[1], r2[0]), play(bracket[2], r2[1]),
            play(bracket[3], r2[2]), play(bracket[4], r2[3]),
        )
        val sf = arrayOf(play(qf[0], qf[3]), play(qf[1], qf[2]))
        return play(sf[0], sf[1])
    }

    /**
     * 10^x. kotlin.math.pow is the multiplatform one; java.lang.Math is not
     * reachable from common code, and neither is a pow10 - there isn't one.
     */
    private fun pow10(x: Double): Double = 10.0.pow(x)
}

/**
 * Where the simulator's inputs are kept: a small file beside the database,
 * written from the bundled seed and from every sync, newest wins.
 *
 * Kept out of Seeder.merge so its signature does not change - merge is called
 * from two places and covered by tests that would all have to move.
 */
object SimulationStore {
    private const val FILE = "simulation.json"

    fun save(device: Device, root: JSONObject) {
        val sim = root.optJSONObject("simulation") ?: return
        val generatedAt = root.optString("generatedAt")
        val stored = load(device)
        if (stored != null && stored.optString("generatedAt") > generatedAt) return
        device.writeFile(
            FILE,
            JSONObject().put("generatedAt", generatedAt).put("simulation", sim).toString()
        )
    }

    fun load(device: Device): JSONObject? =
        device.readFile(FILE)?.let { runCatching { JSONObject(it) }.getOrNull() }

    fun inputs(device: Device): SeasonSimulator.Inputs? =
        load(device)?.optJSONObject("simulation")?.let { SeasonSimulator.parse(it) }
}
