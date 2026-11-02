package com.richclickermacro.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MacroDiagnosticsTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        assertTrue(MacroDiagnostics.flush())
        File(context.filesDir, "macro-diagnostics.log").delete()
        File(context.filesDir, "macro-diagnostics.previous.log").delete()
    }

    private fun exportText(): String {
        val output = ByteArrayOutputStream()
        MacroDiagnostics.export(context, output)
        return output.toString(Charsets.UTF_8.name())
    }

    @Test
    fun recordsAndExportsEvents() {
        MacroDiagnostics.record(context, "macro_start", "points=2")
        MacroDiagnostics.record(context, "macro_stop", "stop_requested")
        val text = exportText()
        assertTrue(text.contains("macro_start"))
        assertTrue(text.contains("macro_stop"))
        assertTrue(text.contains("points=2"))
        assertTrue(text.indexOf("macro_start") < text.indexOf("macro_stop"))
    }

    @Test
    fun exportWithoutEventsIsStillValid() {
        assertTrue(exportText().contains("Belum ada kejadian"))
    }

    @Test
    fun criticalEventsAreWrittenBeforeReturning() {
        MacroDiagnostics.recordCritical(context, "uncaught_exception", "type=TestError")
        val raw = File(context.filesDir, "macro-diagnostics.log").readText()
        assertTrue(raw.contains("uncaught_exception"))
        assertTrue(raw.contains("type=TestError"))
    }

    @Test
    fun detailsAreBoundedAndNewlinesCannotForgeExtraEvents() {
        MacroDiagnostics.record(context, "test_event", "x".repeat(600) + "\nsecret_next_line")
        // record() is asynchronous by contract; wait for the worker instead of
        // racing it. Without this the test only passed when the worker won.
        assertTrue(MacroDiagnostics.flush(10_000L))
        val line = File(context.filesDir, "macro-diagnostics.log").readText().trim()
        assertFalse(line.contains("\n"))
        assertFalse(line.contains("secret_next_line"))
        assertTrue(line.endsWith("x".repeat(500)))
    }

    @Test
    fun longSessionsRotateWithoutUnboundedDiskGrowth() {
        repeat(800) { index ->
            MacroDiagnostics.record(context, "memory_sample", "index=$index " + "x".repeat(490))
            // Keep the queue bounded while deterministically exercising rotation.
            if (index % 32 == 0) assertTrue(MacroDiagnostics.flush(10_000L))
        }
        assertTrue(MacroDiagnostics.flush(10_000L))
        val current = File(context.filesDir, "macro-diagnostics.log")
        val previous = File(context.filesDir, "macro-diagnostics.previous.log")
        assertTrue(previous.exists())
        assertTrue(current.length() <= 256L * 1024L + 2048L)
        assertTrue(previous.length() <= 256L * 1024L + 2048L)
        assertTrue(exportText().contains("memory_sample"))
    }

    @Test
    fun memorySamplesUseTheSameBoundedExportableLog() {
        MacroDiagnostics.recordMemory(context, "memory_sample", 3)
        val text = exportText()
        assertTrue(text.contains("memory_sample"))
        assertTrue(text.contains("overlayWindows=3"))
        assertTrue(text.contains("javaUsedKb="))
        assertTrue(text.contains("clicker only"))
    }

    @Test
    fun flushWaitsForQueuedEventsWithoutExplicitSleep() {
        repeat(20) { MacroDiagnostics.record(context, "queued_event", "index=$it") }
        assertTrue(MacroDiagnostics.flush(10_000L))
        assertEquals(20, Regex("queued_event").findAll(exportText()).count())
    }
}
