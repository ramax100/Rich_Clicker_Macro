package com.richclickermacro.quicksettings

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.richclickermacro.MainActivity
import com.richclickermacro.accessibility.AutoClickAccessibilityService

class MacroTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) {
            unlockAndRun { handleTileClick() }
            return
        }
        handleTileClick()
    }

    private fun handleTileClick() {
        val accessibilityService = AutoClickAccessibilityService.instance
        when {
            accessibilityService == null -> openMainActivity()
            // A running runner always wins so the tile can stop it.
            accessibilityService.isSmartActive() -> {
                accessibilityService.toggleSmartFromTile()
                updateTileState()
            }
            accessibilityService.isMacroActive() -> {
                accessibilityService.toggleFromTile()
                updateTileState()
            }
            accessibilityService.hasLoadedSmartProfile() -> {
                accessibilityService.toggleSmartFromTile()
                updateTileState()
            }
            accessibilityService.hasLoadedProfile() -> {
                accessibilityService.toggleFromTile()
                updateTileState()
            }
            else -> openMainActivity()
        }
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val accessibilityService = AutoClickAccessibilityService.instance
        val smartActive = accessibilityService?.isSmartActive() == true
        val classicActive = accessibilityService?.isMacroActive() == true
        val active = smartActive || classicActive
        val smartLoaded = accessibilityService?.hasLoadedSmartProfile() == true
        val loaded = smartLoaded || accessibilityService?.hasLoadedProfile() == true
        val smartMode = smartActive || (!classicActive && smartLoaded)
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (smartMode) "Smart Click" else "Rich Clicker"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                active && smartMode -> "Smart jalan"
                active -> "Berjalan"
                loaded && smartMode -> "Smart siap"
                loaded -> "Siap"
                else -> "Buka aplikasi"
            }
            tile.contentDescription = when {
                active -> "Hentikan macro Rich Clicker Macro"
                loaded -> "Mulai macro Rich Clicker Macro"
                else -> "Buka aplikasi Rich Clicker Macro"
            }
        }
        tile.updateTile()
    }

    // The legacy Intent overload is reached only below API 34; API 34+ uses PendingIntent.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openMainActivity() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        runCatching {
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
    }

    companion object {
        fun requestRefresh(context: Context) {
            requestListeningState(context, ComponentName(context, MacroTileService::class.java))
        }
    }
}
