package com.richclickermacro.smart

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * "Ambil warna ulang" (recapture colour) reportedly did nothing.
 *
 * Two causes are covered here:
 *  1. The screenshot was taken while our own settings dialog was still fading out, so a
 *     valid colour of the wrong thing was stored. A reading is now only accepted when a
 *     second reading of the same patch agrees with it.
 *  2. The capture latch could stay set, making every later attempt return silently. The
 *     latch logic and the "point by id" rule are checked here as far as they are pure.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class SmartRecaptureTest {

    private lateinit var repository: SmartRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("smart_profiles", Context.MODE_PRIVATE).edit().clear().commit()
        repository = SmartRepository(context)
    }

    // ------------------------------------------------------- stable-reading rules

    @Test
    fun identicalReadingsAreAccepted() {
        val red = 0xFFD32F2F.toInt()
        assertTrue(ColorCapture.readingsAgree(red, red, 24))
    }

    @Test
    fun tinyNoiseFromDitheringIsAccepted() {
        val first = 0xFF3366CC.toInt()
        val second = 0xFF3364CC.toInt()
        assertTrue(ColorCapture.readingsAgree(first, second, 24))
    }

    @Test
    fun aFadingDialogIsRejected() {
        // What actually happened: game colour on the first read, dialog colour on the
        // second. Two readings that far apart must never be stored as the target.
        val gameRed = 0xFFD32F2F.toInt()
        val dialogWhite = 0xFFF8FAFC.toInt()
        assertFalse(ColorCapture.readingsAgree(gameRed, dialogWhite, 24))
    }

    @Test
    fun aMovingAnimationIsRejected() {
        assertFalse(ColorCapture.readingsAgree(0xFF102030.toInt(), 0xFF203040.toInt(), 12))
    }

    @Test
    fun SlackNeverExceedsTheSafetyCeiling() {
        // Even a very insensitive point must not accept a reading 60 levels away.
        assertFalse(ColorCapture.readingsAgree(0xFF000000.toInt(), 0xFF3C3C3C.toInt(), 120))
        assertTrue(ColorCapture.readingsAgree(0xFF000000.toInt(), 0xFF282828.toInt(), 120))
    }

    @Test
    fun alphaIsIgnoredWhenComparingReadings() {
        assertTrue(ColorCapture.readingsAgree(0x00FF0000, 0xFFFF0000.toInt(), 24))
    }

    @Test
    fun unstableMessageShowsBothColours() {
        val message = ColorCapture.unstableMessage(0xFFD32F2F.toInt(), 0xFFF8FAFC.toInt())
        assertTrue(message.contains("#D32F2F"))
        assertTrue(message.contains("#F8FAFC"))
        assertTrue("the user must be told what to do", message.contains("diam"))
    }

    // --------------------------------------------- recapturing touches one point only

    private fun profileWithTwoPoints(): SmartProfile {
        val created = repository.createProfile("Ulang")
        return repository.updateProfile(
            created.copy(
                points = listOf(
                    SmartPoint(id = 11L, label = "T1", watchX = 0.2f, watchY = 0.2f, targetColor = 0xFF111111.toInt(), colorCaptured = true),
                    SmartPoint(id = 22L, label = "T2", watchX = 0.8f, watchY = 0.8f, targetColor = 0xFF222222.toInt(), colorCaptured = true)
                )
            )
        )!!
    }

    @Test
    fun recapturingOnePointLeavesTheOtherUntouched() {
        val before = profileWithTwoPoints()
        val newColour = 0xFF00C853.toInt()
        val edited = before.copy(
            points = before.points.map {
                if (it.id == 11L) it.copy(targetColor = newColour, colorCaptured = true) else it
            }
        )
        val saved = repository.updateProfile(edited)!!
        assertEquals(newColour, saved.points.first { it.id == 11L }.targetColor)
        assertEquals(before.points.first { it.id == 22L }.targetColor, saved.points.first { it.id == 22L }.targetColor)
    }

    @Test
    fun theNewColourSurvivesReload() {
        val before = profileWithTwoPoints()
        val newColour = 0xFF00C853.toInt()
        repository.updateProfile(
            before.copy(points = before.points.map { it.copy(targetColor = newColour) })
        )
        // A fresh repository instance reads from storage, exactly like a restart.
        val reloaded = SmartRepository(ApplicationProvider.getApplicationContext()).getProfiles()
            .first { it.name == before.name }
        assertTrue(reloaded.points.all { it.targetColor == newColour })
    }

    @Test
    fun recaptureKeepsThePointPositionAndLabel() {
        val before = profileWithTwoPoints()
        val edited = before.copy(
            points = before.points.map {
                if (it.id == 22L) it.copy(targetColor = 0xFF123456.toInt()) else it
            }
        )
        val saved = repository.updateProfile(edited)!!
        val point = saved.points.first { it.id == 22L }
        assertEquals(0.8f, point.watchX, 0.0001f)
        assertEquals(0.8f, point.watchY, 0.0001f)
        assertEquals("T2", point.label)
    }

    @Test
    fun pointsAreStillResolvedByUniqueIdAfterRecapture() {
        val before = profileWithTwoPoints()
        val edited = before.copy(
            points = before.points.map { it.copy(targetColor = 0xFFABCDEF.toInt()) }
        )
        val saved = repository.updateProfile(edited)!!
        val ids = saved.points.map { it.id }
        assertEquals(2, ids.toSet().size)
        assertNotEquals(saved.points[0].id, saved.points[1].id)
    }
}
