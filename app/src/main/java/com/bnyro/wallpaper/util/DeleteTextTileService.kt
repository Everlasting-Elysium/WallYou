package com.bnyro.wallpaper.util

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.net.toUri
import androidx.core.os.postDelayed
import com.bnyro.wallpaper.widget.WallpaperWidgetProvider
import com.bnyro.wallpaper.widget.WidgetPrefs
import java.io.BufferedReader
import java.io.InputStreamReader

@RequiresApi(Build.VERSION_CODES.N)
class DeleteTextTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile.state = Tile.STATE_INACTIVE
        qsTile.updateTile()
    }

    override fun onClick() {
        super.onClick()

        val manager = AppWidgetManager.getInstance(this)
        val ids = manager.getAppWidgetIds(
            ComponentName(this, WallpaperWidgetProvider::class.java)
        )

        // Find the first text-mode widget
        val widgetId = ids.firstOrNull { id ->
            WidgetPrefs.getMode(this, id) == WidgetPrefs.MODE_TEXT
        }

        if (widgetId != null) {
            val currentLine = WidgetPrefs.getCurrentLine(this, widgetId)
            val txtUri = WidgetPrefs.getTxtUri(this, widgetId)

            if (currentLine != null && txtUri != null) {
                try {
                    val uri = txtUri.toUri()
                    // Read all lines
                    val lines = contentResolver.openInputStream(uri)?.use { stream ->
                        BufferedReader(InputStreamReader(stream)).readLines()
                    } ?: emptyList()

                    // Remove the first occurrence of currentLine
                    val mutableLines = lines.toMutableList()
                    mutableLines.remove(currentLine)

                    // Write back
                    contentResolver.openOutputStream(uri, "wt")?.use { stream ->
                        stream.write(mutableLines.joinToString("\n").toByteArray())
                    }

                    Log.d(TAG, "Deleted text line: $currentLine")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to delete text line", e)
                }
            }

            // Refresh widget with next random line
            WallpaperWidgetProvider.updateWidget(this, manager, widgetId)
        }

        qsTile.state = Tile.STATE_ACTIVE
        qsTile.updateTile()

        Handler(Looper.getMainLooper()).postDelayed(3000) {
            qsTile.state = Tile.STATE_INACTIVE
            qsTile.updateTile()
        }
    }

    companion object {
        private const val TAG = "DeleteTextTile"
    }
}
