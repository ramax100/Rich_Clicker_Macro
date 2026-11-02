package com.richclickermacro.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClickPreferencesTest {
    @Test
    fun parsesValidDelaySeconds() {
        assertEquals(100L, ClickPreferences.parseDelaySeconds("0.1"))
        assertEquals(1_500L, ClickPreferences.parseDelaySeconds("1.5"))
        assertEquals(1_500L, ClickPreferences.parseDelaySeconds("1,5"))
        assertEquals(3_600_000L, ClickPreferences.parseDelaySeconds("3600.0"))
    }

    @Test
    fun rejectsInvalidDelaySeconds() {
        assertNull(ClickPreferences.parseDelaySeconds(""))
        assertNull(ClickPreferences.parseDelaySeconds("-1"))
        assertNull(ClickPreferences.parseDelaySeconds("0.0"))
        assertNull(ClickPreferences.parseDelaySeconds("3600.1"))
        assertNull(ClickPreferences.parseDelaySeconds("1.25"))
        assertNull(ClickPreferences.parseDelaySeconds("abc"))
    }

    @Test
    fun sanitizesDelayToHundredMillisBounds() {
        assertEquals(100L, ClickPreferences.sanitizeDelay(0))
        assertEquals(1_000L, ClickPreferences.sanitizeDelay(950))
        assertEquals(1_100L, ClickPreferences.sanitizeDelay(1_050))
        assertEquals(3_600_000L, ClickPreferences.sanitizeDelay(9_999_999))
    }

    @Test
    fun quantizesActionDurationWithinBounds() {
        assertEquals(600L, ClickPreferences.quantizeToStep(100, 600, 60_000))
        assertEquals(700L, ClickPreferences.quantizeToStep(749, 100, 60_000))
        assertEquals(60_000L, ClickPreferences.quantizeToStep(999_999, 100, 60_000))
    }

    @Test
    fun formatsDelaySecondsConsistently() {
        assertEquals("1.0", ClickPreferences.formatDelaySeconds(1_000))
        assertEquals("0.1", ClickPreferences.formatDelaySeconds(100))
        assertEquals("60.0", ClickPreferences.formatDelaySeconds(60_000))
    }
}
