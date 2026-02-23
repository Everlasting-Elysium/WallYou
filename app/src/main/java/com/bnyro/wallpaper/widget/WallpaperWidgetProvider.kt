package com.bnyro.wallpaper.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.core.net.toUri
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.bnyro.wallpaper.R
import com.bnyro.wallpaper.enums.WallpaperSource
import com.bnyro.wallpaper.util.BackgroundWorker
import com.bnyro.wallpaper.util.LocalWallpaperHelper
import com.bnyro.wallpaper.util.Preferences
import com.bnyro.wallpaper.util.ShuffleQueue
import java.io.BufferedReader
import java.io.InputStreamReader

class WallpaperWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (id in appWidgetIds) {
            updateWidget(context, appWidgetManager, id)
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for (id in appWidgetIds) {
            WidgetPrefs.remove(context, id)
            alarmManager.cancel(refreshPendingIntent(context, id))
        }
    }

    override fun onDisabled(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, WallpaperWidgetProvider::class.java))
        for (id in ids) {
            alarmManager.cancel(refreshPendingIntent(context, id))
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        when (intent.action) {
            ACTION_CLICK -> {
                val oneTimeJob = OneTimeWorkRequestBuilder<BackgroundWorker>().build()
                WorkManager.getInstance(context)
                    .enqueueUniqueWork(WIDGET_WORKER_KEY, ExistingWorkPolicy.REPLACE, oneTimeJob)
            }
            ACTION_REFRESH -> {
                val widgetId = intent.getIntExtra(
                    AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID
                )
                if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    val manager = AppWidgetManager.getInstance(context)
                    updateWidget(context, manager, widgetId)
                }
            }
        }
    }

    companion object {
        private const val TAG = "WallpaperWidget"
        private const val ACTION_CLICK = "com.bnyro.wallpaper.widget.ACTION_CLICK"
        private const val ACTION_REFRESH = "com.bnyro.wallpaper.widget.ACTION_REFRESH"
        private const val WIDGET_WORKER_KEY = "widget_worker_key"

        fun updateWidget(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val mode = WidgetPrefs.getMode(context, widgetId)
            val views = RemoteViews(context.packageName, R.layout.widget_layout)

            val clickIntent = Intent(context, WallpaperWidgetProvider::class.java).apply {
                action = ACTION_CLICK
            }
            val clickPending = PendingIntent.getBroadcast(
                context, widgetId, clickIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, clickPending)

            when (mode) {
                WidgetPrefs.MODE_TRANSPARENT -> {
                    views.setViewVisibility(R.id.widget_image, View.GONE)
                    views.setViewVisibility(R.id.widget_text, View.GONE)
                }
                WidgetPrefs.MODE_TEXT -> {
                    views.setViewVisibility(R.id.widget_image, View.GONE)
                    views.setViewVisibility(R.id.widget_text, View.VISIBLE)
                    val line = readRandomLine(context, widgetId)
                    views.setTextViewText(R.id.widget_text, line ?: "")
                }
                WidgetPrefs.MODE_IMAGE -> {
                    views.setViewVisibility(R.id.widget_text, View.GONE)
                    views.setViewVisibility(R.id.widget_image, View.VISIBLE)
                    val bitmap = loadImageForWidget(context)
                    if (bitmap != null) {
                        views.setImageViewBitmap(R.id.widget_image, bitmap)
                    } else {
                        views.setViewVisibility(R.id.widget_image, View.GONE)
                    }
                }
            }

            manager.updateAppWidget(widgetId, views)
        }

        fun scheduleRefresh(context: Context, widgetId: Int) {
            val minutes = WidgetPrefs.getRefreshMinutes(context, widgetId)
            val intervalMs = minutes.toLong() * 60 * 1000
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.setRepeating(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + intervalMs,
                intervalMs,
                refreshPendingIntent(context, widgetId)
            )
        }

        fun cancelRefresh(context: Context, widgetId: Int) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.cancel(refreshPendingIntent(context, widgetId))
        }

        private fun refreshPendingIntent(context: Context, widgetId: Int): PendingIntent {
            val intent = Intent(context, WallpaperWidgetProvider::class.java).apply {
                action = ACTION_REFRESH
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            }
            return PendingIntent.getBroadcast(
                context, REFRESH_REQUEST_BASE + widgetId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private const val REFRESH_REQUEST_BASE = 10000

        private fun readRandomLine(context: Context, widgetId: Int): String? {
            val uriString = WidgetPrefs.getTxtUri(context, widgetId) ?: return null
            return try {
                val uri = uriString.toUri()
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val lines = BufferedReader(InputStreamReader(stream)).readLines()
                        .filter { it.isNotBlank() }
                    if (lines.isEmpty()) return null
                    lines.random()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read txt file", e)
                null
            }
        }

        private fun loadImageForWidget(context: Context): Bitmap? {
            val configs = Preferences.getWallpaperConfigs()
            val localConfig = configs.firstOrNull { it.source == WallpaperSource.LOCAL }

            if (localConfig != null && localConfig.localFolderUris.isNotEmpty()) {
                return try {
                    val walls = LocalWallpaperHelper.getLocalWalls(context, localConfig)
                    if (walls.isEmpty()) return null
                    val keyMap = walls.associateBy { LocalWallpaperHelper.toStableKey(it) }
                    val pickedKey = ShuffleQueue.pickNext(
                        context, "widget_image", keyMap.keys
                    ) ?: return null
                    val selected = keyMap[pickedKey] ?: return null
                    loadAndScaleBitmap(context, selected.file.uri)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load local image for widget", e)
                    null
                }
            }

            return try {
                val wallpaperManager = android.app.WallpaperManager.getInstance(context)
                val drawable = wallpaperManager.drawable ?: return null
                val bitmap = (drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                bitmap?.let { scaleBitmapForWidget(context, it) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get current wallpaper", e)
                null
            }
        }

        private fun getScreenMaxDim(context: Context): Int {
            val dm = context.resources.displayMetrics
            return maxOf(dm.widthPixels, dm.heightPixels)
        }

        private fun loadAndScaleBitmap(context: Context, uri: android.net.Uri): Bitmap? {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, opts)
            }
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return null

            val maxDim = getScreenMaxDim(context)
            var sampleSize = 1
            while (opts.outWidth / sampleSize > maxDim || opts.outHeight / sampleSize > maxDim) {
                sampleSize *= 2
            }

            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            return context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOpts)
            }
        }

        private fun scaleBitmapForWidget(context: Context, bitmap: Bitmap): Bitmap {
            val maxDim = getScreenMaxDim(context)
            if (bitmap.width <= maxDim && bitmap.height <= maxDim) return bitmap
            val scale = maxDim.toFloat() / maxOf(bitmap.width, bitmap.height)
            return Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt(),
                (bitmap.height * scale).toInt(),
                true
            )
        }
    }
}
