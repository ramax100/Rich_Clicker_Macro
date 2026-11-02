package com.richclickermacro.smart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorMatcherTest {
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun distanceIgnoresAlpha() {
        val opaque = rgb(10, 20, 30)
        val transparent = (0x10 shl 24) or (10 shl 16) or (20 shl 8) or 30
        assertEquals(0, ColorMatcher.distance(opaque, transparent))
    }

    @Test
    fun distanceUsesLargestChannelDifference() {
        assertEquals(40, ColorMatcher.distance(rgb(100, 100, 100), rgb(140, 110, 90)))
    }

    @Test
    fun matchRespectsToleranceBoundary() {
        val target = rgb(200, 50, 50)
        assertTrue(ColorMatcher.isMatch(rgb(210, 50, 50), target, 10))
        assertFalse(ColorMatcher.isMatch(rgb(211, 50, 50), target, 10))
    }

    @Test
    fun changeIsTheInverseOfMatchAtTheSameTolerance() {
        val baseline = rgb(20, 20, 20)
        val sample = rgb(35, 20, 20)
        assertFalse(ColorMatcher.isChanged(sample, baseline, 15))
        assertTrue(ColorMatcher.isChanged(sample, baseline, 14))
    }

    @Test
    fun averageOfEmptyPatchIsNullNotEmptyBlack() {
        assertNull(ColorMatcher.average(IntArray(0)))
    }

    @Test
    fun averageRoundsToNearestAndForcesOpaqueAlpha() {
        val pixels = intArrayOf(rgb(10, 10, 10), rgb(11, 11, 11))
        val average = ColorMatcher.average(pixels)!!
        assertEquals(11, ColorMatcher.red(average))
        assertEquals(255, (average ushr 24) and 0xFF)
    }

    @Test
    fun averageSuppressesSinglePixelNoise() {
        val pixels = IntArray(36) { rgb(100, 150, 200) }
        pixels[7] = rgb(255, 255, 255)
        val average = ColorMatcher.average(pixels)!!
        assertTrue(ColorMatcher.isMatch(average, rgb(100, 150, 200), 8))
    }

    @Test
    fun hexRoundTrip() {
        val color = rgb(0x12, 0xAB, 0xEF)
        assertEquals("#12ABEF", ColorMatcher.toHex(color))
        assertEquals(color, ColorMatcher.parseHex("#12ABEF"))
        assertEquals(color, ColorMatcher.parseHex("12abef"))
    }

    @Test
    fun parseHexSupportsShortAndAlphaForms() {
        assertEquals(rgb(0xAA, 0xBB, 0xCC), ColorMatcher.parseHex("#ABC"))
        assertEquals(rgb(0x11, 0x22, 0x33), ColorMatcher.parseHex("#FF112233"))
    }

    @Test
    fun parseHexRejectsGarbage() {
        assertNull(ColorMatcher.parseHex("bukan warna"))
        assertNull(ColorMatcher.parseHex("#12345"))
        assertNull(ColorMatcher.parseHex("#GGHHII"))
        assertNull(ColorMatcher.parseHex(""))
    }
}
