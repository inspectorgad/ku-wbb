package com.example

import com.example.ui.GLOSSARY
import com.example.ui.STAT_COLUMNS
import com.example.ui.explain
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The glossary is only useful if it actually covers what is on screen. A
 * column added to the stats table without a definition is exactly the silent
 * gap this catches — the press-and-hold simply does nothing and nobody knows
 * why.
 */
class GlossaryTest {

    @Test
    fun `every stats table column has a definition`() {
        val missing = STAT_COLUMNS.map { it.first }.filter { explain(it) == null }
        assertTrue("no definition for: $missing", missing.isEmpty())
    }

    @Test
    fun `the derived efficiency figures are all explained`() {
        for (label in listOf("eFG%", "TS%", "3PAr", "FTr", "A/TO", "Margin")) {
            assertNotNull("no definition for $label", explain(label))
        }
    }

    @Test
    fun `definitions are sentences, not restatements of the abbreviation`() {
        for ((label, definition) in GLOSSARY) {
            assertTrue("$label is too terse: $definition", definition.length > 12)
            assertTrue("$label should read as prose", definition.trimEnd().endsWith("."))
        }
    }

    @Test
    fun `an unknown label has no definition rather than an empty one`() {
        assertNull(explain("XYZ"))
        assertNull(explain(""))
    }
}
