package com.richclickermacro.data

import android.content.ActivityNotFoundException
import android.content.Intent

/**
 * Launches the first picker the device can actually handle.
 *
 * Android 12 devices/ROMs without a usable DocumentsUI throw ActivityNotFoundException
 * from startActivityForResult. Previously that exception escaped and force-closed the
 * app. Every candidate is now attempted inside a guard, and total failure is reported
 * as a value instead of an exception.
 */
object SafePickerLauncher {
    sealed class Result {
        data class Launched(val intent: Intent) : Result()
        data class Unavailable(val reasons: List<String>) : Result()
    }

    fun launchFirstAvailable(candidates: List<Intent>, start: (Intent) -> Unit): Result {
        require(candidates.isNotEmpty()) { "At least one picker intent is required" }
        val reasons = mutableListOf<String>()
        for (intent in candidates) {
            val action = intent.action ?: "intent"
            try {
                start(intent)
                return Result.Launched(intent)
            } catch (_: ActivityNotFoundException) {
                reasons.add("$action: tidak ada aplikasi penangan")
            } catch (_: SecurityException) {
                reasons.add("$action: ditolak oleh sistem")
            } catch (error: RuntimeException) {
                // Some OEM pickers fail inside the framework before opening.
                // Contain launch failures here; never catch VM errors.
                reasons.add("$action: ${error.javaClass.simpleName}")
            }
        }
        return Result.Unavailable(reasons)
    }
}
