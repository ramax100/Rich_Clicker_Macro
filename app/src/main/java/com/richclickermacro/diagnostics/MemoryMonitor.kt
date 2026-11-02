package com.richclickermacro.diagnostics

import android.os.Debug
import java.util.Locale

/**
 * On-device memory readings for the clicker process only. Never inspects the game.
 * PSS is a point-in-time estimate, not a diagnosis of the game's crash and not a
 * complete measure of GPU/compositor allocations. No universal "safe RAM" cutoff.
 */
object MemoryMonitor {
    data class Snapshot(
        val pssKb: Long,
        val javaUsedKb: Long,
        val javaMaxKb: Long,
        val nativeHeapKb: Long,
        val overlayWindows: Int
    )

    /** Call off the main thread: reading PSS can be relatively expensive. */
    fun capture(overlayWindows: Int): Snapshot {
        val runtime = Runtime.getRuntime()
        val pss = runCatching { Debug.getPss() }.getOrDefault(0L).coerceAtLeast(0L)
        val native = runCatching { Debug.getNativeHeapAllocatedSize() / 1024L }
            .getOrDefault(0L).coerceAtLeast(0L)
        return Snapshot(
            pssKb = pss,
            javaUsedKb = ((runtime.totalMemory() - runtime.freeMemory()) / 1024L).coerceAtLeast(0L),
            javaMaxKb = (runtime.maxMemory() / 1024L).coerceAtLeast(0L),
            nativeHeapKb = native,
            overlayWindows = overlayWindows.coerceAtLeast(0)
        )
    }

    fun megabytes(kb: Long): String =
        String.format(Locale.US, "%.1f", kb.coerceAtLeast(0L) / 1024.0)

    fun format(snapshot: Snapshot): String =
        "pss=${if (snapshot.pssKb > 0) megabytes(snapshot.pssKb) + "MiB" else "unavailable"}" +
            " java=${megabytes(snapshot.javaUsedKb)}/${megabytes(snapshot.javaMaxKb)}MiB" +
            " native=${megabytes(snapshot.nativeHeapKb)}MiB" +
            " overlayWindows=${snapshot.overlayWindows}" +
            " pssKb=${snapshot.pssKb} javaUsedKb=${snapshot.javaUsedKb}" +
            " javaMaxKb=${snapshot.javaMaxKb} nativeHeapKb=${snapshot.nativeHeapKb}"

    fun display(snapshot: Snapshot): String = buildString {
        append("PSS clicker: ")
        append(if (snapshot.pssKb > 0) "${megabytes(snapshot.pssKb)} MiB" else "tidak terbaca")
        append("\nJava terpakai: ${megabytes(snapshot.javaUsedKb)} MiB")
        append("\nBatas heap Java: ${megabytes(snapshot.javaMaxKb)} MiB")
        append("\nNative heap: ${megabytes(snapshot.nativeHeapKb)} MiB")
        append("\nWindow overlay dikelola: ${snapshot.overlayWindows}")
        append("\n\nIni proses clicker, bukan RAM game atau RAM seluruh HP. ")
        append("Batas heap bukan jumlah RAM yang sedang dipakai. PSS juga bukan diagnosis penyebab crash.")
    }
}
