package com.richclickermacro.smart

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression tests for the "memilih kecepatan cuma kedip" bug.
 *
 * Root cause: the overlay's persist() sent the edited profile to a callback wired to
 * SmartRepository.updatePoints(), which only replaces the point list and returns the
 * STORED profile. The controller then assigned that return value back to currentProfile,
 * so every profile-level edit (speed, loop count, idle guard, timings) was instantly
 * overwritten with the old value. The dialog redrew with the old selection, which looked
 * like a blink.
 *
 * The fix is SmartRepository.updateProfile(), which saves the whole profile.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class SmartOverlayPersistTest {

    private lateinit var context: Context
    private lateinit var repository: SmartRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("smart_profiles", Context.MODE_PRIVATE).edit().clear().commit()
        repository = SmartRepository(context)
    }

    /** Mirrors what SmartOverlayController.persist() does through the service callback. */
    private fun persistLikeOverlay(edited: SmartProfile): SmartProfile {
        val returned = repository.updateProfile(edited)
        return returned ?: edited
    }

    @Test
    fun choosingFastActuallySticks() {
        val start = repository.getActiveProfile()
        assertEquals(SmartSpeedMode.RECOMMENDED, start.speedMode)

        val edited = SmartLimits.applySpeed(start, SmartSpeedMode.FAST)
        val afterPersist = persistLikeOverlay(edited)

        assertEquals("the value the overlay keeps must be the new one", SmartSpeedMode.FAST, afterPersist.speedMode)
        assertEquals(SmartSpeedMode.FAST, SmartRepository(context).getActiveProfile().speedMode)
    }

    @Test
    fun choosingRecommendedBackFromFastAlsoSticks() {
        persistLikeOverlay(SmartLimits.applySpeed(repository.getActiveProfile(), SmartSpeedMode.FAST))
        val back = persistLikeOverlay(
            SmartLimits.applySpeed(repository.getActiveProfile(), SmartSpeedMode.RECOMMENDED)
        )
        assertEquals(SmartSpeedMode.RECOMMENDED, back.speedMode)
        assertEquals(SmartSpeedMode.RECOMMENDED, SmartRepository(context).getActiveProfile().speedMode)
    }

    @Test
    fun everySpeedChoiceSurvivesThePersistRoundTrip() {
        SmartSpeedMode.values().forEach { mode ->
            val edited = SmartLimits.applySpeed(repository.getActiveProfile(), mode)
            val after = persistLikeOverlay(edited)
            assertEquals("choice $mode was reverted", mode, after.speedMode)
            assertEquals(mode, SmartRepository(context).getActiveProfile().speedMode)
        }
    }

    @Test
    fun theOldPointsOnlyPathIsWhatCausedTheRevert() {
        // Documents the bug so it cannot be reintroduced by rewiring the callback.
        val start = repository.getActiveProfile()
        val edited = SmartLimits.applySpeed(start, SmartSpeedMode.FAST)
        val pointsOnly = repository.updatePoints(edited.name, edited.points)
        assertNotNull(pointsOnly)
        assertEquals(
            "updatePoints intentionally ignores profile settings",
            SmartSpeedMode.RECOMMENDED, pointsOnly!!.speedMode
        )
    }

    @Test
    fun loopCountEditIsNoLongerReverted() {
        val edited = repository.getActiveProfile().copy(loopLimit = 12)
        val after = persistLikeOverlay(edited)
        assertEquals(12, after.loopLimit)
        assertEquals(12, SmartRepository(context).getActiveProfile().loopLimit)
    }

    @Test
    fun unlimitedLoopCanBeRestoredAfterACustomCount() {
        persistLikeOverlay(repository.getActiveProfile().copy(loopLimit = 12))
        val after = persistLikeOverlay(repository.getActiveProfile().copy(loopLimit = 0))
        assertEquals(0, after.loopLimit)
        assertEquals(0, SmartRepository(context).getActiveProfile().loopLimit)
    }

    @Test
    fun disablingTheIdleGuardIsNoLongerReverted() {
        val edited = repository.getActiveProfile().copy(idleTimeoutSec = 0)
        val after = persistLikeOverlay(edited)
        assertEquals("this is why it kept stopping after 120 s", 0, after.idleTimeoutSec)
        assertEquals(0, SmartRepository(context).getActiveProfile().idleTimeoutSec)
    }

    @Test
    fun maxActionsEditIsNoLongerReverted() {
        val after = persistLikeOverlay(repository.getActiveProfile().copy(maxActions = 500))
        assertEquals(500, after.maxActions)
        assertEquals(500, SmartRepository(context).getActiveProfile().maxActions)
    }

    @Test
    fun customTimingEditsAreNoLongerReverted() {
        val edited = SmartLimits.sanitizeProfile(
            repository.getActiveProfile().copy(
                speedMode = SmartSpeedMode.CUSTOM,
                scanIntervalMs = 900L,
                cooldownMs = 3_000L,
                confirmSamples = 4
            )
        )
        val after = persistLikeOverlay(edited)
        assertEquals(SmartSpeedMode.CUSTOM, after.speedMode)
        assertEquals(900L, after.scanIntervalMs)
        assertEquals(3_000L, after.cooldownMs)
        assertEquals(4, after.confirmSamples)
        val reloaded = SmartRepository(context).getActiveProfile()
        assertEquals(900L, reloaded.scanIntervalMs)
        assertEquals(3_000L, reloaded.cooldownMs)
        assertEquals(4, reloaded.confirmSamples)
    }

    @Test
    fun pointEditsStillWorkAlongsideSettings() {
        val point = SmartPoint(
            id = 1L, label = "T1", watchX = 0.3f, watchY = 0.7f,
            repeatCount = 6, action = SmartAction.LONG_PRESS, holdMs = 1_200L
        )
        val edited = SmartLimits.applySpeed(
            repository.getActiveProfile().copy(points = listOf(point)), SmartSpeedMode.FAST
        )
        val after = persistLikeOverlay(edited)
        assertEquals(SmartSpeedMode.FAST, after.speedMode)
        val saved = after.points.single()
        assertEquals(6, saved.repeatCount)
        assertEquals(SmartAction.LONG_PRESS, saved.action)
        assertEquals(1_200L, saved.holdMs)
        assertEquals(0.3f, saved.watchX, 0.0001f)
    }

    @Test
    fun anOverlayEditCannotRenameOrDuplicateTheProfile() {
        val start = repository.getActiveProfile()
        val countBefore = repository.getProfiles().size
        val after = persistLikeOverlay(start.copy(loopLimit = 3))
        assertEquals(start.name, after.name)
        assertEquals(countBefore, repository.getProfiles().size)
    }

    @Test
    fun theActiveProfilePointerIsPreserved() {
        val first = repository.getActiveProfile()
        val second = repository.createProfile("Kedua")
        repository.setActiveProfile(second.name)
        // Editing the first profile must not silently switch the active one.
        persistLikeOverlay(first.copy(loopLimit = 9))
        assertEquals(second.name, SmartRepository(context).getActiveProfile().name)
        assertEquals(9, repository.getProfiles().first { it.name == first.name }.loopLimit)
    }

    @Test
    fun updatingAMissingProfileReturnsNullInsteadOfCreatingOne() {
        val ghost = repository.getActiveProfile().copy(name = "TidakAda")
        assertNull(repository.updateProfile(ghost))
        assertEquals(1, repository.getProfiles().size)
    }

    @Test
    fun persistedValuesAreStillClamped() {
        val after = persistLikeOverlay(
            repository.getActiveProfile().copy(
                scanIntervalMs = 1L, cooldownMs = 1L, confirmSamples = 99, loopLimit = -4
            )
        )
        assertEquals(SmartProfile.MIN_SCAN_INTERVAL_MS, after.scanIntervalMs)
        assertEquals(SmartProfile.MIN_COOLDOWN_MS, after.cooldownMs)
        assertEquals(SmartProfile.MAX_CONFIRM_SAMPLES, after.confirmSamples)
        assertEquals(0, after.loopLimit)
    }

    @Test
    fun classicProfilesAreUntouchedByTheFix() {
        val classic = context.getSharedPreferences("macro_profiles", Context.MODE_PRIVATE)
        classic.edit().putString("sentinel", "untouched").commit()
        persistLikeOverlay(SmartLimits.applySpeed(repository.getActiveProfile(), SmartSpeedMode.FAST))
        assertEquals(
            "untouched",
            context.getSharedPreferences("macro_profiles", Context.MODE_PRIVATE).getString("sentinel", null)
        )
    }
}
