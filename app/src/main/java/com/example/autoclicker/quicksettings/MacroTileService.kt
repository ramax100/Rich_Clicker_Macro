package com.example.autoclicker.quicksettings

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.example.autoclicker.MainActivity
import com.example.autoclicker.accessibility.AutoClickAccessibilityService
import com.example.autoclicker.diagnostics.DiagnosticLog

class MacroTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val accessibilityService = AutoClickAccessibilityService.instance
        DiagnosticLog.record(
            this,
            "tile_clicked",
            mapOf(
                "locked" to isLocked,
                "serviceAvailable" to (accessibilityService != null),
                "macroActive" to (accessibilityService?.isMacroActive() == true),
                "profileLoaded" to (accessibilityService?.hasLoadedProfile() == true)
            )
        )
        if (accessibilityService?.isMacroActive() == true) {
            accessibilityService.toggleFromTile()
            updateTileState()
            return
        }
        if (isLocked) {
            unlockAndRun { handleTileClick() }
            return
        }
        handleTileClick()
    }

    private fun handleTileClick() {
        val accessibilityService = AutoClickAccessibilityService.instance
        if (accessibilityService?.hasLoadedProfile() == true) {
            accessibilityService.toggleFromTile()
            updateTileState()
        } else {
            openMainActivity()
        }
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val accessibilityService = AutoClickAccessibilityService.instance
        val pending = accessibilityService?.isMacroStartPending() == true
        val stoppedForSystemUi = accessibilityService?.wasStoppedForSystemUi() == true
        val active = accessibilityService?.isMacroActive() == true
        val loaded = accessibilityService?.hasLoadedProfile() == true
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Rich Clicker"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                pending -> "Menunggu aplikasi"
                stoppedForSystemUi -> "Sudah dihentikan"
                active -> "Berjalan"
                loaded -> "Siap"
                else -> "Buka aplikasi"
            }
            tile.contentDescription = when {
                pending -> "Batalkan mulai macro Rich Clicker Macro"
                stoppedForSystemUi -> "Macro Rich Clicker Macro sudah dihentikan"
                active -> "Hentikan macro Rich Clicker Macro"
                loaded -> "Mulai macro Rich Clicker Macro"
                else -> "Buka aplikasi Rich Clicker Macro"
            }
        }
        tile.updateTile()
    }

    private fun openMainActivity() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        fun requestRefresh(context: Context) {
            requestListeningState(context, ComponentName(context, MacroTileService::class.java))
        }
    }
}
