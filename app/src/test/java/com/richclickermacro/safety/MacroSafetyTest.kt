package com.richclickermacro.safety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MacroSafetyTest {
    private val frame = ScreenFrame(left = 0, top = 0, width = 1080, height = 2400, rotation = 0)

    @Test
    fun markerCenterMatchesNormalizedScreenCoordinate() {
        val size = 44
        val left = MarkerCoordinates.localLeft(0.5f, frame, size)
        val top = MarkerCoordinates.localTop(0.25f, frame, size)
        assertTrue(MarkerCoordinates.isAligned(frame, 0.5f, 0.25f, left, top, size, size))
    }

    @Test
    fun nonZeroDisplayOriginIsIncludedInExpectedCenter() {
        val offsetFrame = frame.copy(left = 120, top = 64)
        assertEquals(660f, offsetFrame.centerX(0.5f), 0.001f)
        assertEquals(664f, offsetFrame.centerY(0.25f), 0.001f)
        // A marker laid out as if the display started at zero must be rejected.
        assertFalse(MarkerCoordinates.isAligned(offsetFrame, 0.5f, 0.25f, 518, 578, 44, 44))
        assertTrue(MarkerCoordinates.isAligned(offsetFrame, 0.5f, 0.25f, 638, 642, 44, 44))
    }

    @Test
    fun statusBarSizedDriftIsRejected() {
        assertFalse(MarkerCoordinates.isAligned(frame, 0.5f, 0.25f, 518, 624, 44, 44))
    }

    @Test
    fun roundingWithinTwoPixelsIsAccepted() {
        assertTrue(MarkerCoordinates.isAligned(frame, 0.5f, 0.25f, 519, 579, 44, 44))
    }

    @Test
    fun unmeasuredMarkerIsRejected() {
        assertFalse(MarkerCoordinates.isAligned(frame, 0.5f, 0.25f, 518, 578, 0, 0))
    }

    @Test
    fun edgeCoordinatesRemainReachable() {
        assertEquals(-22, MarkerCoordinates.localLeft(0f, frame, 44))
        assertEquals(1058, MarkerCoordinates.localLeft(1f, frame, 44))
        assertEquals(-22, MarkerCoordinates.localTop(0f, frame, 44))
        assertEquals(2378, MarkerCoordinates.localTop(1f, frame, 44))
    }

    @Test
    fun normalizedRoundTripPreservesInteriorPoint() {
        val x = MarkerCoordinates.normalizedX(540f, frame)
        val y = MarkerCoordinates.normalizedY(600f, frame)
        assertEquals(0.5f, x, 0.001f)
        assertEquals(0.25f, y, 0.001f)
    }

    @Test
    fun rotationAndBoundsChangesProduceDifferentSnapshots() {
        assertFalse(frame == frame.copy(rotation = 1))
        assertFalse(frame == frame.copy(width = 2400, height = 1080))
        assertFalse(frame == frame.copy(left = 10))
    }

    @Test
    fun gameWindowIsRecognized() {
        assertTrue(
            ForegroundWindowClassifier.isApplicationWindow(
                "example.game", "example.game.GameActivity", OWN, MAIN, "example.keyboard"
            )
        )
    }

    @Test
    fun ownOverlayDialogIsNotAnApplicationSwitch() {
        assertFalse(
            ForegroundWindowClassifier.isApplicationWindow(
                OWN, "android.app.AlertDialog", OWN, MAIN, null
            )
        )
        assertTrue(
            ForegroundWindowClassifier.isApplicationWindow(OWN, MAIN, OWN, MAIN, null)
        )
    }

    @Test
    fun transientSystemWindowsDoNotReplaceTarget() {
        assertFalse(ForegroundWindowClassifier.isApplicationWindow("com.android.systemui", "shade", OWN, MAIN, null))
        assertFalse(ForegroundWindowClassifier.isApplicationWindow("example.keyboard", "IME", OWN, MAIN, "example.keyboard"))
        assertFalse(ForegroundWindowClassifier.isApplicationWindow("android", "android.widget.Toast", OWN, MAIN, null))
    }

    @Test
    fun android12PermissionInstallerAndChooserDialogsDoNotReplaceTarget() {
        assertFalse(
            ForegroundWindowClassifier.isApplicationWindow(
                "com.android.permissioncontroller", "com.android.permissioncontroller.permission.ui.GrantPermissionsActivity", OWN, MAIN, null
            )
        )
        assertFalse(
            ForegroundWindowClassifier.isApplicationWindow(
                "com.google.android.permissioncontroller", "com.android.permissioncontroller.permission.ui.GrantPermissionsActivity", OWN, MAIN, null
            )
        )
        assertFalse(
            ForegroundWindowClassifier.isApplicationWindow(
                "com.android.packageinstaller", "com.android.packageinstaller.InstallStaging", OWN, MAIN, null
            )
        )
        assertFalse(
            ForegroundWindowClassifier.isApplicationWindow(
                "android", "com.android.internal.app.ChooserActivity", OWN, MAIN, null
            )
        )
        assertFalse(
            ForegroundWindowClassifier.isApplicationWindow(
                "example.game", "android.app.Dialog", OWN, MAIN, null
            )
        )
    }

    @Test
    fun launcherIsAnApplicationSwitch() {
        assertTrue(
            ForegroundWindowClassifier.isApplicationWindow(
                "com.android.launcher3", "com.android.launcher3.Launcher", OWN, MAIN, null
            )
        )
    }

    @Test
    fun missingMetadataIsIgnored() {
        assertFalse(ForegroundWindowClassifier.isApplicationWindow(null, "Activity", OWN, MAIN, null))
        assertFalse(ForegroundWindowClassifier.isApplicationWindow("example.game", null, OWN, MAIN, null))
    }

    private companion object {
        const val OWN = "com.richclickermacro"
        const val MAIN = "com.richclickermacro.MainActivity"
    }
}
