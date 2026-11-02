package com.richclickermacro.smart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Regression tests for "the click landed somewhere else".
 *
 * Multi Smart Click must use ONE coordinate space: normalized [0,1] over the full
 * display. The picker, the marker window, the sampled pixel and the dispatched
 * gesture all have to agree, including when the display frame has a non-zero origin.
 */
class SmartAccuracyTest {

    private val markerSize = 132 // ~44 dp at 3x density

    /** Where the sampler reads, expressed in capture pixels. */
    private fun sampledPixel(normalized: Float, captureSize: Int): Int =
        (normalized.coerceIn(0f, 1f) * captureSize).toInt().coerceIn(0, captureSize - 1)

    @Test
    fun markerRoundTripIsExactAtZeroOrigin() {
        val width = 1080
        for (nx in listOf(0f, 0.1f, 0.25f, 0.5f, 0.73f, 0.99f, 1f)) {
            val left = SmartGeometry.markerLeft(nx, 0, width, markerSize)
            val back = SmartGeometry.fromMarkerLeft(left, markerSize, 0, width)
            assertTrue("round trip drifted at $nx", abs(back - nx) < 0.001f)
        }
    }

    @Test
    fun markerRoundTripIsExactWithNonZeroOrigin() {
        // This is the case that used to break: markers ignored the frame origin.
        val originX = 0
        val originY = 88
        val height = 2160
        for (ny in listOf(0f, 0.2f, 0.5f, 0.81f, 1f)) {
            val top = SmartGeometry.markerTop(ny, originY, height, markerSize)
            val back = SmartGeometry.fromMarkerTop(top, markerSize, originY, height)
            assertTrue("vertical round trip drifted at $ny", abs(back - ny) < 0.001f)
        }
        val left = SmartGeometry.markerLeft(0.4f, originX, 1080, markerSize)
        assertTrue(abs(SmartGeometry.fromMarkerLeft(left, markerSize, originX, 1080) - 0.4f) < 0.001f)
    }

    @Test
    fun markerCentreEqualsTheGestureTarget() {
        val width = 1080
        val height = 2340
        val originY = 0
        for (nx in listOf(0.15f, 0.5f, 0.87f)) {
            val left = SmartGeometry.markerLeft(nx, 0, width, markerSize)
            val centre = left + markerSize / 2f
            val gestureX = SmartGeometry.absoluteX(nx, 0, width)
            assertTrue("marker centre and gesture must coincide", abs(centre - gestureX) <= 1f)
        }
        for (ny in listOf(0.1f, 0.5f, 0.95f)) {
            val top = SmartGeometry.markerTop(ny, originY, height, markerSize)
            val centre = top + markerSize / 2f
            val gestureY = SmartGeometry.absoluteY(ny, originY, height)
            assertTrue(abs(centre - gestureY) <= 1f)
        }
    }

    @Test
    fun sampledPixelMatchesTheGestureTargetOnAFullDisplayCapture() {
        // With the frame covering the full display, normalized means the same place
        // in the screenshot and on the screen.
        val width = 1080
        val height = 2340
        for (n in listOf(0.1f, 0.33f, 0.5f, 0.8f, 0.97f)) {
            val sampled = sampledPixel(n, width)
            val clicked = SmartGeometry.absoluteX(n, 0, width).roundToInt()
            assertTrue("horizontal sample/click disagree at $n", abs(sampled - clicked) <= 1)
            val sampledY = sampledPixel(n, height)
            val clickedY = SmartGeometry.absoluteY(n, 0, height).roundToInt()
            assertTrue("vertical sample/click disagree at $n", abs(sampledY - clickedY) <= 1)
        }
    }

    @Test
    fun aFrameSmallerThanTheCaptureIsDetectedAsMismatched() {
        // The old bug: frame excluded insets (shorter) while the capture was full height.
        // Same aspect ratio is fine; a different shape must be rejected.
        assertFalse(
            "a cropped frame must be flagged",
            SmartGeometry.captureMatchesFrame(1080, 2340, 1080, 2100)
        )
        assertFalse(
            SmartGeometry.captureMatchesFrame(1080, 2340, 1080, 1920)
        )
    }

    @Test
    fun matchingShapesAreAcceptedEvenAtDifferentScale() {
        // A uniformly scaled capture still maps correctly because we normalize.
        assertTrue(SmartGeometry.captureMatchesFrame(1080, 2340, 1080, 2340))
        assertTrue(SmartGeometry.captureMatchesFrame(540, 1170, 1080, 2340))
        assertTrue(SmartGeometry.captureMatchesFrame(720, 1560, 1080, 2340))
    }

    @Test
    fun rotatedCaptureIsRejected() {
        assertFalse(SmartGeometry.captureMatchesFrame(2340, 1080, 1080, 2340))
    }

    @Test
    fun invalidSizesAreRejected() {
        assertFalse(SmartGeometry.captureMatchesFrame(0, 0, 1080, 2340))
        assertFalse(SmartGeometry.captureMatchesFrame(1080, 2340, 0, 0))
        assertFalse(SmartGeometry.captureMatchesFrame(-1, 100, 1080, 2340))
    }

    @Test
    fun pickerTapMapsToTheSamePlaceTheGestureClicks() {
        // The picker normalizes an absolute touch; the gesture de-normalizes it again.
        val width = 1080
        val height = 2340
        val originY = 63
        for (rawY in listOf(100f, 800f, 1500f, 2300f)) {
            val ny = SmartGeometry.normalize(rawY, originY, height)
            val clicked = SmartGeometry.absoluteY(ny, originY, height)
            assertTrue("picker and gesture disagree for $rawY", abs(clicked - rawY) <= 1f)
        }
        for (rawX in listOf(10f, 540f, 1070f)) {
            val nx = SmartGeometry.normalize(rawX, 0, width)
            assertTrue(abs(SmartGeometry.absoluteX(nx, 0, width) - rawX) <= 1f)
        }
    }

    @Test
    fun aPointAlwaysClicksWhereItWatches() {
        // The separate-tap option was removed in 0.9.2. A point can no longer watch
        // one place and click another, which was the main cause of wrong-target clicks.
        val point = SmartPoint(id = 1L, label = "T1", watchX = 0.2f, watchY = 0.3f)
        assertEquals(0.2f, point.effectiveTapX(), 0.0001f)
        assertEquals(0.3f, point.effectiveTapY(), 0.0001f)
        val moved = point.copy(watchX = 0.75f, watchY = 0.6f)
        assertEquals(0.75f, moved.effectiveTapX(), 0.0001f)
        assertEquals(0.6f, moved.effectiveTapY(), 0.0001f)
    }

    @Test
    fun tinyRoundingDriftDoesNotInvalidateACapturedColour() {
        // syncFromViews compares with an epsilon so a sub-pixel difference from
        // window rounding does not silently drop the stored target colour.
        val epsilon = 0.0015f
        val stored = 0.5f
        val rounded = SmartGeometry.fromMarkerLeft(
            SmartGeometry.markerLeft(stored, 0, 1080, markerSize), markerSize, 0, 1080
        )
        assertTrue("rounding drift must stay under the epsilon", abs(stored - rounded) < epsilon)
    }

    @Test
    fun outOfRangePositionsAreClampedNotWrapped() {
        assertEquals(0f, SmartGeometry.fromMarkerLeft(-9999, markerSize, 0, 1080), 0.0001f)
        assertEquals(1f, SmartGeometry.fromMarkerLeft(9999, markerSize, 0, 1080), 0.0001f)
        assertEquals(0.5f, SmartGeometry.fromMarkerLeft(10, markerSize, 0, 0), 0.0001f)
    }
}
