package io.glucostride.core

import org.junit.Assert.assertEquals
import org.junit.Test

class GlucoseUnitTest {
    @Test
    fun unitsAndSignedTrendAreExplicit() {
        assertEquals("7.0", GlucoseUnit.MMOL_L.value(126))
        assertEquals("126", GlucoseUnit.MG_DL.value(126))
        assertEquals("-1.50", GlucoseUnit.MG_DL.rate(-150))
        assertEquals("+0.10", GlucoseUnit.MMOL_L.rate(180))
    }
}
