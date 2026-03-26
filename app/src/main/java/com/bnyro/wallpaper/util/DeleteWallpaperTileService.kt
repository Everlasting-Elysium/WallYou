package com.bnyro.wallpaper.util

import android.content.ContentValues
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.net.toUri
import androidx.core.os.postDelayed
import androidx.documentfile.provider.DocumentFile
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

@RequiresApi(Build.VERSION_CODES.N)
class DeleteWallpaperTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile.state = Tile.STATE_INACTIVE
        qsTile.updateTile()
    }

    override fun onClick() {
        super.onClick()

        val uriString = Preferences.getCurrentWallpaperUri()

        if (uriString != null) {
            val safUri = uriString.toUri()
            var trashed = false

            // API 30+: try MediaStore trash (goes to gallery "Recently Deleted")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                trashed = trashViaMediaStore(safUri)
            }

            // Fallback: SAF direct delete
            if (!trashed) {
                try {
                    DocumentFile.fromSingleUri(this, safUri)?.delete()
                } catch (e: Exception) {
                    Log.e(TAG, "SAF delete failed", e)
                }
            }

            Preferences.setCurrentWallpaper(null, null, null)
        }

        // Trigger next wallpaper
        val oneTimeJob = OneTimeWorkRequestBuilder<BackgroundWorker>().build()
        WorkManager.getInstance(this)
            .enqueueUniqueWork(TILE_WORKER_KEY, ExistingWorkPolicy.REPLACE, oneTimeJob)

        qsTile.state = Tile.STATE_ACTIVE
        qsTile.updateTile()

        Handler(Looper.getMainLooper()).postDelayed(3000) {
            qsTile.state = Tile.STATE_INACTIVE
            qsTile.updateTile()
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun trashViaMediaStore(safUri: android.net.Uri): Boolean {
        return try {
            val mediaUri = MediaStore.getMediaUri(this, safUri) ?: return false

            // Try silent IS_TRASHED update first
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_TRASHED, 1)
            }
            val rows = contentResolver.update(mediaUri, values, null, null)
            if (rows > 0) {
                Log.d(TAG, "Silently trashed via IS_TRASHED")
                return true
            }

            // If silent update didn't work, use createTrashRequest (system dialog)
            val request = MediaStore.createTrashRequest(
                contentResolver, listOf(mediaUri), true
            )
            request.intentSender.sendIntent(this, 0, null, null, null)
            Log.d(TAG, "Sent createTrashRequest")
            true
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore trash failed", e)
            false
        }
    }

    companion object {
        private const val TAG = "DeleteWallpaperTile"
        private const val TILE_WORKER_KEY = "delete_wallpaper_tile_worker"
    }
}
