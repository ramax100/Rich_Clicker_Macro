package com.richclickermacro.diagnostics

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import java.io.File
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Local-only operational log: macro events and window package names, never screen
 * contents or screenshots. One background writer, a bounded pending queue, and
 * two rotating files keep hours of logging from becoming an unbounded RAM/disk cost.
 * Export explicitly waits for pending entries. Fatal errors use a synchronous path.
 */
object MacroDiagnostics {
    private const val LOG_NAME = "macro-diagnostics.log"
    private const val PREVIOUS_NAME = "macro-diagnostics.previous.log"
    private const val MAX_FILE_BYTES = 256L * 1024L
    private const val MAX_PENDING = 64
    private const val MAX_DETAILS = 500
    private const val MAX_CRITICAL_DETAILS = 2_000

    private val workerLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pending = AtomicInteger(0)
    private val dropped = AtomicInteger(0)
    private var worker: Handler? = null

    fun record(context: Context, event: String, details: String = "") {
        val appContext = context.applicationContext ?: context
        val line = formatLine(event, details, MAX_DETAILS)
        enqueue { append(appContext, line) }
    }

    /** Must finish writing before Android's default handler terminates a crashing process. */
    fun recordCritical(context: Context, event: String, details: String = "") {
        runCatching { append(context.applicationContext ?: context, formatLine(event, details, MAX_CRITICAL_DETAILS)) }
    }

    /** Captures PSS and writes the log on the same background thread, never on the UI thread. */
    fun recordMemory(context: Context, event: String, overlayWindows: Int) {
        val appContext = context.applicationContext ?: context
        enqueue {
            runCatching {
                append(appContext, formatLine(event, MemoryMonitor.format(MemoryMonitor.capture(overlayWindows)), MAX_DETAILS))
            }
        }
    }

    /** Used by the explicit "Cek RAM" button; the callback is delivered on the main thread. */
    fun measureMemory(context: Context, overlayWindows: Int, callback: (MemoryMonitor.Snapshot?) -> Unit): Boolean {
        val appContext = context.applicationContext ?: context
        return enqueue {
            runCatching {
                val snapshot = MemoryMonitor.capture(overlayWindows)
                append(appContext, formatLine("memory_check", MemoryMonitor.format(snapshot), MAX_DETAILS))
                mainHandler.post { callback(snapshot) }
            }.onFailure {
                mainHandler.post { callback(null) }
            }
        }
    }

    private fun formatLine(event: String, details: String, limit: Int): String {
        val safeEvent = event.replace('\n', ' ').replace('\r', ' ').take(100)
        val safeDetails = details.replace('\n', ' ').replace('\r', ' ').take(limit)
        return "${Instant.now()} | $safeEvent | $safeDetails\n"
    }

    private fun enqueue(task: () -> Unit): Boolean {
        while (true) {
            val count = pending.get()
            if (count >= MAX_PENDING) {
                dropped.incrementAndGet()
                return false
            }
            if (pending.compareAndSet(count, count + 1)) break
        }
        return runCatching {
            val handler = synchronized(workerLock) {
                worker ?: run {
                    val thread = HandlerThread("rich-clicker-diagnostics", Process.THREAD_PRIORITY_BACKGROUND)
                    thread.start()
                    Handler(thread.looper).also { worker = it }
                }
            }
            handler.post {
                try {
                    task()
                } finally {
                    pending.decrementAndGet()
                }
            }
        }.getOrElse {
            pending.decrementAndGet()
            dropped.incrementAndGet()
            false
        }
    }

    @Synchronized
    private fun append(context: Context, line: String) {
        runCatching {
            val directory = context.filesDir
            val current = File(directory, LOG_NAME)
            if (current.length() >= MAX_FILE_BYTES) {
                val previous = File(directory, PREVIOUS_NAME)
                if (previous.exists()) previous.delete()
                if (!current.renameTo(previous)) {
                    // A failed rotation must not allow the current file to grow forever.
                    current.outputStream().use { it.channel.truncate(0L) }
                }
            }
            val lost = dropped.getAndSet(0)
            val prefix = if (lost > 0) formatLine("diagnostics_events_dropped", "count=$lost", MAX_DETAILS) else ""
            current.appendText(prefix + line)
        }
    }

    /** Wait briefly for normal exports; do not indefinitely block the UI on a stuck filesystem. */
    fun flush(timeoutMs: Long = 2_000L): Boolean {
        if (pending.get() == 0) return true
        val done = CountDownLatch(1)
        val handler = synchronized(workerLock) { worker } ?: return false
        val posted = runCatching { handler.post { done.countDown() } }.getOrDefault(false)
        if (!posted) return false
        return runCatching { done.await(timeoutMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS) }.getOrDefault(false)
    }

    fun export(context: Context, output: java.io.OutputStream) {
        val flushed = flush()
        synchronized(this) {
            output.bufferedWriter().use { writer ->
                writer.write("Rich Clicker Macro diagnostics (local events; no screen content)\n")
                writer.write("Memory readings describe the clicker only; PSS excludes some GPU/compositor memory.\n")
                if (!flushed) writer.write("Sebagian kejadian terbaru masih diproses; coba export lagi sesaat.\n")
                val previous = File(context.filesDir, PREVIOUS_NAME)
                val current = File(context.filesDir, LOG_NAME)
                if (previous.exists()) previous.inputStream().bufferedReader().use { it.copyTo(writer) }
                if (current.exists()) current.inputStream().bufferedReader().use { it.copyTo(writer) }
                if (!previous.exists() && !current.exists()) writer.write("Belum ada kejadian tercatat.\n")
            }
        }
    }
}
