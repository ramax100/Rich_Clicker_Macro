package com.richclickermacro

import android.app.Application
import android.os.Build
import android.os.Process
import com.richclickermacro.diagnostics.MacroDiagnostics
import kotlin.system.exitProcess

/**
 * Records fatal clicker-process errors locally before delegating to Android's
 * default crash handler. This does not hide crashes or keep a broken process alive.
 */
class MacroApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        MacroDiagnostics.record(
            this,
            "app_process_start",
            "sdk=${Build.VERSION.SDK_INT} device=${Build.MANUFACTURER}/${Build.MODEL}"
        )
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous !is CrashRecordingHandler) {
            Thread.setDefaultUncaughtExceptionHandler(CrashRecordingHandler(previous))
        }
    }

    private inner class CrashRecordingHandler(
        private val previous: Thread.UncaughtExceptionHandler?
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, error: Throwable) {
            runCatching {
                val stack = error.stackTraceToString().take(1_500)
                MacroDiagnostics.recordCritical(
                    this@MacroApplication,
                    "uncaught_exception",
                    "thread=${thread.name} type=${error.javaClass.name} message=${error.message} stack=$stack"
                )
            }
            previous?.uncaughtException(thread, error) ?: run {
                Process.killProcess(Process.myPid())
                exitProcess(1)
            }
        }
    }
}
