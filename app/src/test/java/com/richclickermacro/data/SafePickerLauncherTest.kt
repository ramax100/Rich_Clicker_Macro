package com.richclickermacro.data

import android.content.ActivityNotFoundException
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class SafePickerLauncherTest {
    @Test
    fun missingDocumentsUiDoesNotThrowAndReportsUnavailable() {
        val candidates = listOf(
            DocumentIntents.openJsonDocument(),
            DocumentIntents.getJsonContent(),
            DocumentIntents.getAnyContent()
        )
        // Reproduces Android 12 without DocumentsUI: every launch throws.
        val result = SafePickerLauncher.launchFirstAvailable(candidates) {
            throw ActivityNotFoundException("No Activity found to handle Intent")
        }
        assertTrue(result is SafePickerLauncher.Result.Unavailable)
        assertEquals(3, (result as SafePickerLauncher.Result.Unavailable).reasons.size)
    }

    @Test
    fun fallsBackToGetContentWhenOpenDocumentUnsupported() {
        val candidates = listOf(
            DocumentIntents.openJsonDocument(),
            DocumentIntents.getJsonContent()
        )
        val attempted = mutableListOf<String>()
        val result = SafePickerLauncher.launchFirstAvailable(candidates) { intent ->
            attempted.add(intent.action!!)
            if (intent.action == Intent.ACTION_OPEN_DOCUMENT) {
                throw ActivityNotFoundException("missing provider")
            }
        }
        assertTrue(result is SafePickerLauncher.Result.Launched)
        assertEquals(Intent.ACTION_GET_CONTENT, (result as SafePickerLauncher.Result.Launched).intent.action)
        assertEquals(listOf(Intent.ACTION_OPEN_DOCUMENT, Intent.ACTION_GET_CONTENT), attempted)
    }

    @Test
    fun securityExceptionIsAlsoContained() {
        val result = SafePickerLauncher.launchFirstAvailable(
            listOf(DocumentIntents.createJsonDocument("profiles.json"))
        ) { throw SecurityException("denied") }
        assertTrue(result is SafePickerLauncher.Result.Unavailable)
    }

    @Test
    fun unexpectedOemLaunchFailureIsContained() {
        val result = SafePickerLauncher.launchFirstAvailable(
            listOf(DocumentIntents.createJsonDocument("profiles.json"))
        ) { throw IllegalStateException("OEM picker failed") }
        assertTrue(result is SafePickerLauncher.Result.Unavailable)
        assertEquals(
            listOf("android.intent.action.CREATE_DOCUMENT: IllegalStateException"),
            (result as SafePickerLauncher.Result.Unavailable).reasons
        )
    }

    @Test
    fun usesFirstWorkingPickerWithoutTryingOthers() {
        val attempted = mutableListOf<String>()
        val result = SafePickerLauncher.launchFirstAvailable(
            listOf(DocumentIntents.openJsonDocument(), DocumentIntents.getJsonContent())
        ) { attempted.add(it.action!!) }
        assertTrue(result is SafePickerLauncher.Result.Launched)
        assertEquals(listOf(Intent.ACTION_OPEN_DOCUMENT), attempted)
    }
}
