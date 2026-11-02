package com.richclickermacro.safety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureSafetyTest {
    private val frame = ScreenFrame(0, 0, 1080, 2400, 0)

    @Test
    fun rejectsNonFiniteCoordinatesAndNonPositiveDuration() {
        assertFalse(GestureSafety.isValidGesture(Float.NaN, 0f, 1f, 1f, 100))
        assertFalse(GestureSafety.isValidGesture(0f, Float.POSITIVE_INFINITY, 1f, 1f, 100))
        assertFalse(GestureSafety.isValidGesture(0f, 0f, 1f, 1f, 0))
        assertTrue(GestureSafety.isValidGesture(0f, 0f, 1f, 1f, 1))
    }

    @Test
    fun clampsFarEdgeToLastAddressablePixel() {
        val (x, y) = GestureSafety.clampToFrame(frame, 1080f, 2400f)
        assertEquals(1079f, x, 0.001f)
        assertEquals(2399f, y, 0.001f)
    }

    @Test
    fun clampsNegativeAndOffsetDisplayCoordinates() {
        val offset = ScreenFrame(100, 50, 800, 600, 1)
        val (x, y) = GestureSafety.clampToFrame(offset, -20f, 9999f)
        assertEquals(100f, x, 0.001f)
        assertEquals(649f, y, 0.001f)
    }

    @Test
    fun segmentCountCoversLongActionsWithoutZeroLengthSegments() {
        assertEquals(1, GestureSafety.segmentCount(40))
        assertEquals(1, GestureSafety.segmentCount(500))
        assertEquals(2, GestureSafety.segmentCount(501))
        assertEquals(120, GestureSafety.segmentCount(60_000))
    }

    @Test
    fun callbackTimeoutIsAlwaysLongerThanSegment() {
        assertTrue(GestureSafety.callbackTimeoutMs(500) > 500)
        assertTrue(GestureSafety.callbackTimeoutMs(1) > 1)
    }

    @org.junit.Test
    fun swipeIntersectionDetectsCrossingTouchingAndMissingToolbar() {
        assertTrue(GestureSafety.segmentIntersectsRect(0f, 50f, 100f, 50f, 40f, 40f, 60f, 60f))
        assertTrue(GestureSafety.segmentIntersectsRect(0f, 40f, 100f, 40f, 40f, 40f, 60f, 60f))
        assertFalse(GestureSafety.segmentIntersectsRect(0f, 20f, 100f, 20f, 40f, 40f, 60f, 60f))
        assertFalse(GestureSafety.segmentIntersectsRect(Float.NaN, 0f, 1f, 1f, 0f, 0f, 2f, 2f))
    }
}
