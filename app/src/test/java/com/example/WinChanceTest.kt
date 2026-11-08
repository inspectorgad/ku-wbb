package com.example

import com.example.ui.chance
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The display half of the win model.
 *
 * These are deliberately the same cases as ChanceTest in
 * scripts/test_win_model.py: the two are twins, and the pair only stays
 * honest if both are asked the same questions.
 */
class WinChanceTest {

    @Test
    fun `no game is certain`() {
        // A number that claims certainty will be remembered on the night it
        // is wrong, and these are forecasts from a rating and a venue.
        assertEquals("over 99%", chance(0.9999))
        assertEquals("over 99%", chance(1.0))
        assertEquals("under 1%", chance(0.0001))
        assertEquals("under 1%", chance(0.0))
    }

    @Test
    fun `ordinary numbers read as percentages`() {
        assertEquals("37%", chance(0.37))
        assertEquals("50%", chance(0.5))
        assertEquals("91%", chance(0.9125))
    }

    @Test
    fun `halves round the same way the rest of the app rounds`() {
        assertEquals("49%", chance(0.485))
        assertEquals("48%", chance(0.4849))
    }

    @Test
    fun `a game with no forecast says nothing rather than zero`() {
        assertEquals("", chance(null))
    }

    @Test
    fun `the top boundary does not print a certainty`() {
        // 0.995 * 100 is exactly 99.5, which roundHalfUp makes 100, so a
        // strict > let this one value through as "100%". Same case as
        // ChanceTest in scripts/test_win_model.py.
        assertEquals("over 99%", chance(0.995))
        assertEquals("99%", chance(0.9949))
        // The low end needs no matching guard: it rounds to 1%, never 0%.
        assertEquals("1%", chance(0.005))
    }
}
