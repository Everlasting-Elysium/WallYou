package com.bnyro.wallpaper.util

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.provider.MediaStore
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.net.toUri

@RequiresApi(Build.VERSION_CODES.N)
class OpenWallpaperTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile.state = Tile.STATE_INACTIVE
        qsTile.updateTile()
    }

    override fun onClick() {
        super.onClick()

        val uriString = Preferences.getCurrentWallpaperUri()
        if (uriString == null) {
            Toast.makeText(this, "No current wallpaper", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val safUri = uriString.toUri()

            // Convert to MediaStore URI so gallery apps can edit and save back correctly
            val viewUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    MediaStore.getMediaUri(this, safUri) ?: safUri
                } catch (e: Exception) {
                    safUri
                }
            } else {
                safUri
            }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(viewUri, "image/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(
                    PendingIntent.getActivity(
                        this, 0, intent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open wallpaper", e)
            Toast.makeText(this, "Failed to open wallpaper", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val TAG = "OpenWallpaperTile"
    }
}
