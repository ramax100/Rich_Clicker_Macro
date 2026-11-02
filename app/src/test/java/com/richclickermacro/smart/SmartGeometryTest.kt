package com.richclickermacro.smart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartGeometryTest {
    private fun point(
        id: Long,
        x: Float = 0.5f,
        y: Float = 0.5f,
        captured: Boolean = true,
        condition: SmartCondition = SmartCondition.COLOR_MATCH,
        enabled: Boolean = true
    ) = SmartPoint(
        id = id, label = "T$id", watchX = x, watchY = y,
        colorCaptured = captured, condition = condition, enabled = enabled
    )

    @Test
    fun patchUnderToolbarIsRejected() {
        val toolbar = SmartGeometry.Box(0.0f, 0.0f, 0.4f, 0.2f)
        val patch = SmartGeometry.patchBox(0.1f, 0.1f, 0.006f)
        assertTrue(SmartGeometry.isPatchObscured(patch, listOf(toolbar)))
    }

    @Test
    fun patchAwayFromToolbarIsAllowed() {
        val toolbar = SmartGeometry.Box(0.0f, 0.0f, 0.4f, 0.2f)
        val patch = SmartGeometry.patchBox(0.8f, 0.8f, 0.006f)
        assertFalse(SmartGeometry.isPatchObscured(patch, listOf(toolbar)))
    }

    @Test
    fun edgeTouchingCountsAsObscured() {
        val toolbar = SmartGeometry.Box(0f, 0f, 0.5f, 0.5f)
        assertTrue(SmartGeometry.isPatchObscured(SmartGeometry.Box(0.5f, 0.5f, 0.6f, 0.6f), listOf(toolbar)))
    }

    @Test
    fun overlappingPatchesAreDetected() {
        assertTrue(SmartGeometry.patchesTooClose(0.5f, 0.5f, 0.505f, 0.505f, 0.012f))
        assertFalse(SmartGeometry.patchesTooClose(0.5f, 0.5f, 0.7f, 0.7f, 0.012f))
    }

    @Test
    fun normalizeClampsAndHandlesZeroSize() {
        assertEquals(0.5f, SmartGeometry.normalize(50f, 0, 100), 0.0001f)
        assertEquals(0f, SmartGeometry.normalize(-10f, 0, 100), 0.0001f)
        assertEquals(1f, SmartGeometry.normalize(500f, 0, 100), 0.0001f)
        assertEquals(0.5f, SmartGeometry.normalize(10f, 0, 0), 0.0001f)
    }

    @Test
    fun normalizeRespectsNonZeroFrameOrigin() {
        assertEquals(0.5f, SmartGeometry.normalize(150f, 100, 100), 0.0001f)
        assertEquals(150f, SmartGeometry.denormalize(0.5f, 100, 100), 0.0001f)
    }

    @Test
    fun validationRequiresAtLeastOneEnabledPoint() {
        val empty = SmartProfile("Uji", emptyList())
        assertNotNull(SmartGeometry.validationProblem(empty))
        val disabled = SmartProfile("Uji", listOf(point(1, enabled = false)))
        assertNotNull(SmartGeometry.validationProblem(disabled))
    }

    @Test
    fun validationRequiresCapturedColorForMatchMode() {
        val profile = SmartProfile("Uji", listOf(point(1, captured = false)))
        val problem = SmartGeometry.validationProblem(profile)
        assertNotNull(problem)
        assertTrue(problem!!.contains("warna target"))
    }

    @Test
    fun changeModeDoesNotRequireAPreCapturedColor() {
        val profile = SmartProfile(
            "Uji",
            listOf(point(1, captured = false, condition = SmartCondition.COLOR_CHANGE))
        )
        assertNull(SmartGeometry.validationProblem(profile))
    }

    @Test
    fun validProfilePassesValidation() {
        assertNull(SmartGeometry.validationProblem(SmartProfile("Uji", listOf(point(1)))))
    }
}
