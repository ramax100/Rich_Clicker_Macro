package com.example.autoclicker.diagnostics

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

object DiagnosticLog {
    private const val DIRECTORY_NAME = "diagnostics"
    private const val ACTIVE_FILE_NAME = "events.jsonl"
    private const val PREVIOUS_FILE_NAME = "events.1.jsonl"
    private const val MAX_FILE_BYTES = 256 * 1024L
    private const val MAX_FIELDS = 16
    private const val MAX_VALUE_LENGTH = 240

    private val reservedKeys = setOf("schema", "timeUtc", "timeUtcMs", "elapsedMs", "event")
    private val droppedEvents = AtomicLong(0L)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(256),
        { runnable ->
            Thread(runnable, "rich-clicker-diagnostics").apply { isDaemon = true }
        },
        ThreadPoolExecutor.AbortPolicy()
    )

    fun record(context: Context, event: String, fields: Map<String, Any?> = emptyMap()) {
        val appContext = context.applicationContext
        val timestamp = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        submit(
            task = {
                runCatching {
                    appendLine(appContext, buildLine(timestamp, elapsed, event, fields))
                }.onFailure {
                    droppedEvents.incrementAndGet()
                }
            },
            onRejected = { droppedEvents.incrementAndGet() }
        )
    }

    fun snapshot(context: Context, callback: (Result<String>) -> Unit) {
        val appContext = context.applicationContext
        submit(
            task = {
                val result = runCatching { readSnapshot(appContext) }
                mainHandler.post { callback(result) }
            },
            onRejected = {
                mainHandler.post {
                    callback(Result.failure(IllegalStateException("Antrean log sedang penuh")))
                }
            }
        )
    }

    fun export(context: Context, uri: Uri, callback: (Result<Int>) -> Unit) {
        val appContext = context.applicationContext
        submit(
            task = {
                val result = runCatching {
                    val snapshot = readSnapshot(appContext)
                    appContext.contentResolver.openOutputStream(uri, "wt")
                        ?.bufferedWriter(StandardCharsets.UTF_8)
                        ?.use { it.write(snapshot) }
                        ?: error("Dokumen tidak dapat dibuka")
                    snapshot.toByteArray(StandardCharsets.UTF_8).size
                }
                mainHandler.post { callback(result) }
            },
            onRejected = {
                mainHandler.post {
                    callback(Result.failure(IllegalStateException("Antrean log sedang penuh")))
                }
            }
        )
    }

    private fun submit(task: () -> Unit, onRejected: () -> Unit) {
        try {
            executor.execute(Runnable { task() })
        } catch (_: RejectedExecutionException) {
            onRejected()
        }
    }

    private fun buildLine(
        timestamp: Long,
        elapsed: Long,
        event: String,
        fields: Map<String, Any?>
    ): String {
        val json = JSONObject()
            .put("schema", 1)
            .put("timeUtc", Instant.ofEpochMilli(timestamp).toString())
            .put("timeUtcMs", timestamp)
            .put("elapsedMs", elapsed)
            .put("event", sanitize(event))
        fields.entries
            .asSequence()
            .filterNot { sanitizeKey(it.key) in reservedKeys }
            .take(MAX_FIELDS)
            .forEach { (key, value) -> json.put(sanitizeKey(key), sanitizeValue(value)) }
        return json.toString() + "\n"
    }

    private fun sanitizeValue(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is Boolean, is Int, is Long, is Float, is Double -> value
        else -> sanitize(value.toString())
    }

    private fun sanitizeKey(value: String): String = sanitize(value).replace(' ', '_').take(48)

    private fun sanitize(value: String): String = value
        .replace('\n', ' ')
        .replace('\r', ' ')
        .take(MAX_VALUE_LENGTH)

    private fun appendLine(context: Context, line: String) {
        val directory = File(context.filesDir, DIRECTORY_NAME)
        check(directory.exists() || directory.mkdirs()) { "Direktori log tidak dapat dibuat" }
        val active = File(directory, ACTIVE_FILE_NAME)
        val bytes = line.toByteArray(StandardCharsets.UTF_8)
        check(bytes.size <= MAX_FILE_BYTES) { "Event log terlalu besar" }
        check(active.length() + bytes.size <= MAX_FILE_BYTES || rotate(directory, active)) {
            "Rotasi log gagal"
        }
        FileOutputStream(active, true).bufferedWriter(StandardCharsets.UTF_8).use { writer ->
            writer.append(line)
        }
    }

    private fun rotate(directory: File, active: File): Boolean {
        if (!active.exists()) return true
        val previous = File(directory, PREVIOUS_FILE_NAME)
        return runCatching {
            Files.move(active.toPath(), previous.toPath(), StandardCopyOption.REPLACE_EXISTING)
            true
        }.getOrDefault(false)
    }

    private fun readSnapshot(context: Context): String {
        val directory = File(context.filesDir, DIRECTORY_NAME)
        val previous = File(directory, PREVIOUS_FILE_NAME)
        val active = File(directory, ACTIVE_FILE_NAME)
        val content = buildString {
            appendLine("# Rich Clicker Macro diagnostic log")
            appendLine("# Catatan ini merekam observasi Rich Clicker dan tidak dapat membuktikan aplikasi lain mengalami force close.")
            appendLine("# Package aplikasi dicatat untuk membedakan perpindahan target. Isi layar, teks accessibility, nama profil, dan koordinat tidak dicatat.")
            appendLine("# Pencatatan bersifat best-effort; proses yang dihentikan mendadak dapat kehilangan event terakhir.")
            appendLine("# macro_target_changed menunjukkan aplikasi target meninggalkan foreground, bukan bukti crash.")
            appendLine("# gesture_completed hanya berarti Android menyelesaikan gesture accessibility.")
            val pendingDropped = droppedEvents.get()
            if (pendingDropped > 0L) appendLine("# Total event yang gagal dicatat: $pendingDropped")
            listOf(previous, active).filter(File::exists).forEach { file ->
                file.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                    lines.filter { it.isNotBlank() }.forEach(::appendLine)
                }
            }
        }
        return if (listOf(previous, active).any(File::exists)) {
            content
        } else {
            content + "# Belum ada event diagnostik.\n"
        }
    }
}
