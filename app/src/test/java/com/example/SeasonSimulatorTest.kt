package com.example

import com.example.data.SeasonSimulator
import com.example.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app's half of the season simulator.
 *
 * The fixture and the pinned numbers below are the same ones in
 * docs/sim.test.mjs. That is the whole point of this file: the two engines
 * draw from the same generator in the same order, so they must agree run for
 * run, and a phone and the dashboard showing different odds for the same
 * season is the thing being guarded against. If these numbers drift, one side
 * has changed and the pair is the bug - do not simply re-pin them.
 *
 * The fixture is JSON rather than constructed objects so that parse() is
 * exercised too, and a whole 16-team conference because the bracket - the
 * byes, who meets whom, how many wins each seed needs - is the part most
 * likely to be wrong.
 */
class SeasonSimulatorTest {

    private val fixtureJson = """
    {
      "season": "2026-27",
      "confTotal": 8,
      "confTeams": 16,
      "scale": 1.04,
      "kansas": {"confW": 2, "confL": 1, "overallW": 9, "overallL": 2, "rating": 0.82},
      "remaining": [
        {"date":"2027-01-03","opponent":"Baylor","site":"home","conference":true,"key":"baylor","p":0.55},
        {"date":"2027-01-07","opponent":"TCU","site":"away","conference":true,"key":"tcu","p":0.3},
        {"date":"2027-01-11","opponent":"Utah","site":"home","conference":true,"key":"utah","p":0.7},
        {"date":"2027-01-15","opponent":"Houston","site":"away","conference":true,"key":"houston","p":0.45},
        {"date":"2027-01-20","opponent":"Cincinnati","site":"home","conference":true,"key":"cincinnati","p":0.8}
      ],
      "others": [
        {"team":"TCU","key":"tcu","confW":3,"confL":0,"remaining":4,"pVsAverage":0.9195,"rating":1.6},
        {"team":"Baylor","key":"baylor","confW":2,"confL":1,"remaining":4,"pVsAverage":0.8249,"rating":1.2},
        {"team":"Oklahoma State","key":"oklahoma state","confW":2,"confL":1,"remaining":5,"pVsAverage":0.7717,"rating":1.05},
        {"team":"West Virginia","key":"west virginia","confW":2,"confL":1,"remaining":5,"pVsAverage":0.7303,"rating":0.95},
        {"team":"Iowa State","key":"iowa state","confW":2,"confL":1,"remaining":5,"pVsAverage":0.6602,"rating":0.8},
        {"team":"Utah","key":"utah","confW":1,"confL":2,"remaining":4,"pVsAverage":0.5551,"rating":0.6},
        {"team":"Kansas State","key":"kansas state","confW":1,"confL":2,"remaining":5,"pVsAverage":0.5276,"rating":0.55},
        {"team":"Colorado","key":"colorado","confW":1,"confL":2,"remaining":5,"pVsAverage":0.4449,"rating":0.4},
        {"team":"Arizona","key":"arizona","confW":1,"confL":2,"remaining":5,"pVsAverage":0.3911,"rating":0.3},
        {"team":"BYU","key":"byu","confW":1,"confL":2,"remaining":5,"pVsAverage":0.3398,"rating":0.2},
        {"team":"Houston","key":"houston","confW":1,"confL":2,"remaining":4,"pVsAverage":0.292,"rating":0.1},
        {"team":"Arizona State","key":"arizona state","confW":0,"confL":3,"remaining":5,"pVsAverage":0.2094,"rating":-0.1},
        {"team":"Cincinnati","key":"cincinnati","confW":0,"confL":3,"remaining":4,"pVsAverage":0.1454,"rating":-0.3},
        {"team":"UCF","key":"ucf","confW":0,"confL":3,"remaining":5,"pVsAverage":0.0985,"rating":-0.5},
        {"team":"Texas Tech","key":"texas tech","confW":0,"confL":3,"remaining":5,"pVsAverage":0.0656,"rating":-0.7}
      ],
      "unrated": ["Big 12 Tournament"]
    }
    """.trimIndent()

    private fun fixture(): SeasonSimulator.Inputs =
        SeasonSimulator.parse(JSONObject(fixtureJson))!!

    @Test
    fun `the fixture parses into a whole conference`() {
        val f = fixture()
        assertEquals("2026-27", f.season)
        assertEquals(1.04, f.scale, 1e-9)
        assertEquals(5, f.remaining.size)
        assertEquals(15, f.others.size)          // plus Kansas makes 16
        assertEquals(listOf("Big 12 Tournament"), f.unrated)
        assertEquals("away", f.remaining[1].site)
        assertEquals(0.82, f.kansas.rating, 1e-9)
    }

    @Test
    fun `nothing to simulate parses to nothing`() {
        assertNull(SeasonSimulator.parse(JSONObject("""{"kansas":{}}""")))
        assertNull(SeasonSimulator.parse(JSONObject("""{"kansas":{},"remaining":[],"others":[]}""")))
    }

    /**
     * The numbers docs/sim.test.mjs produces for the same fixture.
     *
     * Every one of these comes from the JavaScript engine, not from running
     * this one and writing down what it said. They agree only if the
     * mulberry32 translation is right down to the unsigned shift, and only if
     * both draw in the same order - the games, then the other teams' seasons,
     * then every team's tiebreak, then the bracket.
     */
    @Test
    fun `pinned against docs sim test mjs, run for run`() {
        val r = SeasonSimulator.simulate(fixture(), emptyMap(), 4000)
        assertEquals(4000, r.runs)
        assertEquals(16, r.games)
        assertEquals(11.78725, r.expectedWins, 1e-9)
        assertEquals(4.21275, r.expectedLosses, 1e-9)
        assertEquals(10, r.winsLow)
        assertEquals(12, r.winsMid)
        assertEquals(13, r.winsHigh)
        assertEquals(0.026, r.title, 1e-9)
        assertEquals(0.056, r.shareOfTitle, 1e-9)
        assertEquals(0.30075, r.top4, 1e-9)
        assertEquals(0.87175, r.top8, 1e-9)
        assertEquals(5.78025, r.averagePlace, 1e-9)
        assertEquals(6, r.seedMid)
        assertEquals(3, r.seedLow)
        assertEquals(9, r.seedHigh)
        assertEquals(0.03325, r.autoBid, 1e-9)
    }

    @Test
    fun `a forced win is pinned too`() {
        val r = SeasonSimulator.simulate(fixture(), mapOf(1 to "W"), 4000)
        assertEquals(12.49075, r.expectedWins, 1e-9)
        assertEquals(0.08025, r.title, 1e-9)
        assertEquals(0.5245, r.top4, 1e-9)
        assertEquals(4.44275, r.averagePlace, 1e-9)
        assertEquals(4, r.seedMid)
        assertEquals(0.03825, r.autoBid, 1e-9)
    }

    @Test
    fun `a forced loss is pinned too`() {
        val r = SeasonSimulator.simulate(fixture(), mapOf(1 to "L"), 4000)
        assertEquals(11.49075, r.expectedWins, 1e-9)
        assertEquals(0.0, r.title, 1e-9)
        assertEquals(0.2075, r.top4, 1e-9)
        assertEquals(6.3365, r.averagePlace, 1e-9)
        assertEquals(0.03125, r.autoBid, 1e-9)
    }

    @Test
    fun `the same inputs give the same numbers every time`() {
        assertEquals(
            SeasonSimulator.simulate(fixture(), emptyMap(), 1500),
            SeasonSimulator.simulate(fixture(), emptyMap(), 1500),
        )
    }

    @Test
    fun `winning out pins the record exactly`() {
        val all = SeasonSimulator.simulate(
            fixture(), mapOf(0 to "W", 1 to "W", 2 to "W", 3 to "W", 4 to "W"), 1500)
        assertEquals(14, all.winsLow)
        assertEquals(14, all.winsHigh)
        assertEquals(14.0, all.expectedWins, 1e-9)
        assertEquals(2.0, all.expectedLosses, 1e-9)
    }

    @Test
    fun `every chance is a probability and the outcomes nest`() {
        val r = SeasonSimulator.simulate(fixture(), emptyMap(), 2000)
        for (p in listOf(r.title, r.shareOfTitle, r.top4, r.top8, r.autoBid)) {
            assertTrue("out of range: $p", p in 0.0..1.0)
        }
        assertTrue(r.title <= r.top4 && r.top4 <= r.top8)
        assertTrue(r.title <= r.shareOfTitle)
        assertTrue(r.seedLow <= r.seedMid && r.seedMid <= r.seedHigh)
    }

    @Test
    fun `the bracket answers to strength`() {
        val f = fixture()
        val best = f.copy(kansas = f.kansas.copy(rating = 4.0))
        val worst = f.copy(kansas = f.kansas.copy(rating = -4.0))
        assertTrue(SeasonSimulator.simulate(best, emptyMap(), 2000).autoBid > 0.5)
        assertTrue(SeasonSimulator.simulate(worst, emptyMap(), 2000).autoBid < 0.05)
    }

    @Test
    fun `the automatic place is not the regular season title`() {
        // The Big 12's NCAA place comes from the bracket. Equating the two is
        // the volleyball rule, where there is no conference tournament.
        val r = SeasonSimulator.simulate(fixture(), emptyMap(), 2000)
        assertNotNull(r)
        assertTrue(r.autoBid != r.title)
        assertTrue(r.autoBid > 0.0)
    }
}
