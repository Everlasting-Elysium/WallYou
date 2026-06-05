package com.bnyro.wallpaper.util

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

@RequiresApi(Build.VERSION_CODES.N)
class SafeModeTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        val active = Preferences.isSafeModeActive()
        qsTile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        qsTile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val current = Preferences.getBoolean(Preferences.safeModeActiveKey, false)
        Preferences.edit { putBoolean(Preferences.safeModeActiveKey, !current) }
        val nowActive = Preferences.isSafeModeActive()
        qsTile.state = if (nowActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        qsTile.updateTile()

        val oneTimeJob = OneTimeWorkRequestBuilder<BackgroundWorker>().build()
        WorkManager.getInstance(this)
            .enqueueUniqueWork(TILE_WORKER_KEY, ExistingWorkPolicy.REPLACE, oneTimeJob)
    }

    companion object {
        private const val TILE_WORKER_KEY = "safe_mode_tile_worker"
    }
}
