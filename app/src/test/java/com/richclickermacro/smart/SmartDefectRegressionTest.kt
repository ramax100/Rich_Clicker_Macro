package com.richclickermacro.smart

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression tests for defects found while auditing accuracy (v0.9.5).
 *
 * Each test here failed against the previous code. They exist so the same mistake
 * cannot come back silently.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class SmartDefectRegressionTest {

    private lateinit var repository: SmartRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("smart_profiles", Context.MODE_PRIVATE).edit().clear().commit()
        repository = SmartRepository(context)
    }

    private fun jsonWith(vararg ids: Long): String {
        val points = ids.mapIndexed { index, id ->
            """{"id":$id,"label":"T${index + 1}","watchX":${0.1 + index * 0.2},
               "watchY":${0.1 + index * 0.3},"targetColor":-65536,"colorCaptured":true}"""
        }.joinToString(",")
        return """
            {"kind":"rich-clicker-smart","schemaVersion":3,"active_smart_profile":"Dup",
             "profiles":[{"name":"Dup","scanIntervalMs":400,"cooldownMs":600,"confirmSamples":2,
              "speedMode":"RECOMMENDED","points":[$points]}]}
        """.trimIndent()
    }

    // ---------------------------------------------------------------- defect 1

    @Test
    fun importedPointsNeverShareAnId() {
        repository.importJson(jsonWith(7L, 7L))
        val imported = repository.getProfiles().first { it.name.startsWith("Dup") }
        val ids = imported.points.map { it.id }
        assertEquals("both points must survive", 2, ids.size)
        assertEquals("ids must be unique after import", 2, ids.toSet().size)
    }

    @Test
    fun threeCopiesOfOneIdBecomeThreeDistinctIds() {
        repository.importJson(jsonWith(5L, 5L, 5L))
        val imported = repository.getProfiles().first { it.name.startsWith("Dup") }
        assertEquals(3, imported.points.map { it.id }.toSet().size)
    }

    @Test
    fun distinctIdsAreLeftExactlyAsTheyWere() {
        repository.importJson(jsonWith(41L, 42L))
        val imported = repository.getProfiles().first { it.name.startsWith("Dup") }
        assertEquals(listOf(41L, 42L), imported.points.map { it.id }.sorted())
    }

    /**
     * Documents WHY unique ids matter: the scan builds one patch per id, so a shared
     * id means one point is judged by the other point's colour and clicks the wrong
     * place. This is the shape the runner relies on.
     */
    @Test
    fun uniqueIdsGiveOnePatchPerPoint() {
        repository.importJson(jsonWith(7L, 7L))
        val points = repository.getProfiles().first { it.name.startsWith("Dup") }.points
        val patches = points.associate { it.id to (it.watchX to it.watchY) }
        assertEquals(points.size, patches.size)
        assertTrue(points[0].watchX != points[1].watchX)
    }

    // ---------------------------------------------------------------- defect 2

    @Test
    fun editingAProfileThatWasRenamedElsewhereStillSaves() {
        val first = repository.getProfiles().first()
        // The editor holds the old name; the stored profile has been renamed since.
        val staleEditorCopy = first.copy(name = "Nama Lama", cooldownMs = 1_200L)
        val saved = repository.updateProfile(staleEditorCopy)
        assertNotNull("the edit must not be dropped", saved)
        val stored = repository.getProfiles().first()
        assertEquals("the user's speed choice must be stored", 1_200L, stored.cooldownMs)
        assertEquals("the stored name must not be renamed by an overlay edit", first.name, stored.name)
    }

    @Test
    fun editingAfterTheOnlyProfileWasReplacedStillSaves() {
        val created = repository.createProfile("Sementara")
        repository.deleteProfile(created.name)
        val stale = created.copy(loopLimit = 9)
        val saved = repository.updateProfile(stale)
        assertNotNull(saved)
        assertEquals(9, repository.getProfiles().first().loopLimit)
    }

    @Test
    fun aMatchingNameStillUpdatesThatExactProfile() {
        val second = repository.createProfile("Kedua")
        val firstBefore = repository.getProfiles().first { it.name != "Kedua" }
        repository.updateProfile(second.copy(loopLimit = 4))
        val firstAfter = repository.getProfiles().first { it.name == firstBefore.name }
        assertEquals("the other profile must not be touched", firstBefore.loopLimit, firstAfter.loopLimit)
        assertEquals(4, repository.getProfiles().first { it.name == "Kedua" }.loopLimit)
    }

    // ---------------------------------------------------------------- defect 3

    @Test
    fun aTwoPercentCropIsRejectedAsAnUnsafeGeometry() {
        // 1080x2400 display, capture missing a 2% slice of height.
        val frameW = 1080
        val frameH = 2400
        val croppedH = (frameH * 0.98).toInt()
        assertFalse(
            "a 2% crop can shift a click by ~48 px and must be refused",
            SmartGeometry.captureMatchesFrame(
                frameW, croppedH, frameW, frameH, 0.01f
            )
        )
    }

    @Test
    fun anIdenticalOrUniformlyScaledCaptureIsAccepted() {
        assertTrue(SmartGeometry.captureMatchesFrame(1080, 2400, 1080, 2400, 0.01f))
        // Same aspect ratio at half resolution is a uniform scale, so normalized
        // positions still mean the same place.
        assertTrue(SmartGeometry.captureMatchesFrame(540, 1200, 1080, 2400, 0.01f))
    }

    @Test
    fun roundingNoiseIsStillAccepted() {
        assertTrue(
            "one pixel of rounding must not stop a run",
            SmartGeometry.captureMatchesFrame(1080, 2401, 1080, 2400, 0.01f)
        )
    }
}
