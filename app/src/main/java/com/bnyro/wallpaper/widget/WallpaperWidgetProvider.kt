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
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
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
                // Change system wallpaper via BackgroundWorker
                val oneTimeJob = OneTimeWorkRequestBuilder<BackgroundWorker>().build()
                WorkManager.getInstance(context)
                    .enqueueUniqueWork(WIDGET_WORKER_KEY, ExistingWorkPolicy.REPLACE, oneTimeJob)
            }
            ACTION_NEXT -> {
                // Load next Q&A line and reset answer visibility
                val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    WidgetPrefs.setAnswerVisible(context, widgetId, false)
                    val manager = AppWidgetManager.getInstance(context)
                    updateWidget(context, manager, widgetId, loadNextLine = true)
                }
            }
            ACTION_TOGGLE_ANSWER -> {
                val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    val hasAnswer = WidgetPrefs.getCurrentAnswer(context, widgetId) != null
                    if (hasAnswer) {
                        val nowVisible = WidgetPrefs.isAnswerVisible(context, widgetId)
                        WidgetPrefs.setAnswerVisible(context, widgetId, !nowVisible)
                        val manager = AppWidgetManager.getInstance(context)
                        updateWidget(context, manager, widgetId, loadNextLine = false)
                    }
                    // no answer → do nothing
                }
            }
            ACTION_OPEN -> openCurrentWallpaper(context)
            ACTION_REFRESH -> {
                val widgetId = intent.getIntExtra(
                    AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID
                )
                if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    WidgetPrefs.setAnswerVisible(context, widgetId, false)
                    val manager = AppWidgetManager.getInstance(context)
                    updateWidget(context, manager, widgetId, loadNextLine = true)
                }
            }
        }
    }

    companion object {
        private const val TAG = "WallpaperWidget"
        private const val ACTION_CLICK = "com.bnyro.wallpaper.widget.ACTION_CLICK"
        private const val ACTION_NEXT = "com.bnyro.wallpaper.widget.ACTION_NEXT"
        private const val ACTION_TOGGLE_ANSWER = "com.bnyro.wallpaper.widget.ACTION_TOGGLE_ANSWER"
        private const val ACTION_OPEN = "com.bnyro.wallpaper.widget.ACTION_OPEN"
        private const val ACTION_REFRESH = "com.bnyro.wallpaper.widget.ACTION_REFRESH"
        private const val WIDGET_WORKER_KEY = "widget_worker_key"
        private const val OPEN_REQUEST_BASE = 20000
        private const val NEXT_REQUEST_BASE = 30000
        private const val ANSWER_REQUEST_BASE = 40000

        fun updateWidget(
            context: Context,
            manager: AppWidgetManager,
            widgetId: Int,
            loadNextLine: Boolean = true,
        ) {
            val mode = WidgetPrefs.getMode(context, widgetId)
            val views = RemoteViews(context.packageName, R.layout.widget_layout)

            // ── Zone 1: change system wallpaper ──────────────────────────
            val changePending = PendingIntent.getBroadcast(
                context, widgetId,
                Intent(context, WallpaperWidgetProvider::class.java).apply { action = ACTION_CLICK },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_click_change, changePending)

            // ── Zone 2: next item ─────────────────────────────────────────
            val nextPending = PendingIntent.getBroadcast(
                context, NEXT_REQUEST_BASE + widgetId,
                Intent(context, WallpaperWidgetProvider::class.java).apply {
                    action = ACTION_NEXT
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_click_next, nextPending)

            // ── Zone 3: show/hide answer (text mode) / open image (other) ─
            val zone3Pending = if (mode == WidgetPrefs.MODE_TEXT) {
                PendingIntent.getBroadcast(
                    context, ANSWER_REQUEST_BASE + widgetId,
                    Intent(context, WallpaperWidgetProvider::class.java).apply {
                        action = ACTION_TOGGLE_ANSWER
                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            } else {
                PendingIntent.getBroadcast(
                    context, OPEN_REQUEST_BASE + widgetId,
                    Intent(context, WallpaperWidgetProvider::class.java).apply { action = ACTION_OPEN },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }
            views.setOnClickPendingIntent(R.id.widget_click_answer, zone3Pending)

            // ── Zone 4: open image ────────────────────────────────────────
            val openPending = PendingIntent.getBroadcast(
                context, OPEN_REQUEST_BASE + widgetId,
                Intent(context, WallpaperWidgetProvider::class.java).apply { action = ACTION_OPEN },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_click_open, openPending)

            // ── Content ───────────────────────────────────────────────────
            when (mode) {
                WidgetPrefs.MODE_TRANSPARENT -> {
                    views.setViewVisibility(R.id.widget_image, View.GONE)
                    views.setViewVisibility(R.id.widget_text, View.GONE)
                }
                WidgetPrefs.MODE_TEXT -> {
                    views.setViewVisibility(R.id.widget_image, View.GONE)
                    views.setViewVisibility(R.id.widget_text, View.VISIBLE)
                    if (loadNextLine) {
                        val (question, answer) = readNextQA(context, widgetId)
                        WidgetPrefs.setCurrentLine(context, widgetId, question)
                        WidgetPrefs.setCurrentAnswer(context, widgetId, answer)
                    }
                    val answerVisible = WidgetPrefs.isAnswerVisible(context, widgetId)
                    val displayText = if (answerVisible) {
                        WidgetPrefs.getCurrentAnswer(context, widgetId)
                            ?: WidgetPrefs.getCurrentLine(context, widgetId)
                    } else {
                        WidgetPrefs.getCurrentLine(context, widgetId)
                    }
                    views.setTextViewText(R.id.widget_text, displayText ?: "")
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
        private fun openCurrentWallpaper(context: Context) {
            val uriString = Preferences.getCurrentWallpaperUri() ?: return
            try {
                val safUri = uriString.toUri()
                val viewUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try {
                        MediaStore.getMediaUri(context, safUri) ?: safUri
                    } catch (_: Exception) {
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
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open wallpaper from widget", e)
            }
        }


        /**
         * Pick next Q&A item.
         *
         * CYCLE phase: 1:1 alternate between Q&A lines (shuffled, no repeat) and
         *              plain lines (random). Reset every 8 hours.
         * POST  phase: pure random from all lines. Entered when all Q&A are exhausted.
         * After 8 hours: always reset to CYCLE regardless of current phase.
         */
        private fun readNextQA(context: Context, widgetId: Int): Pair<String?, String?> {
            val uriString = WidgetPrefs.getTxtUri(context, widgetId) ?: return null to null
            return try {
                val uri = uriString.toUri()
                val allLines = context.contentResolver.openInputStream(uri)?.use { stream ->
                    BufferedReader(InputStreamReader(stream)).readLines().filter { it.isNotBlank() }
                }.orEmpty()
                if (allLines.isEmpty()) return null to null

                val qaLines    = allLines.filter { '|' in it }
                val nonQaLines = allLines.filter { '|' !in it }

                // No Q&A lines → always plain random
                if (qaLines.isEmpty()) return parseLine(allLines.randomOrNull())

                // 8-hour cycle reset
                val now = System.currentTimeMillis()
                val lastReset = WidgetPrefs.getResetTimeMs(context, widgetId)
                if (now - lastReset >= 8 * 60 * 60 * 1000L) {
                    WidgetPrefs.setPhase(context, widgetId, WidgetPrefs.PHASE_CYCLE)
                    WidgetPrefs.setQaRemaining(context, widgetId, emptyList())
                    WidgetPrefs.setIsQaTurn(context, widgetId, true)
                    WidgetPrefs.setResetTimeMs(context, widgetId, now)
                }

                parseLine(pickNextLine(context, widgetId, qaLines, nonQaLines))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read Q&A txt file", e)
                null to null
            }
        }

        /**
         * CYCLE: alternate Q&A / plain 1:1.
         *   - Q&A drawn from a shuffled no-repeat queue; refill triggers POST.
         *   - Plain lines are random (no tracking needed).
         * POST: pure random from all lines.
         */
        private fun pickNextLine(
            context: Context,
            widgetId: Int,
            qaLines: List<String>,
            nonQaLines: List<String>,
        ): String? {
            if (WidgetPrefs.getPhase(context, widgetId) == WidgetPrefs.PHASE_POST) {
                return (qaLines + nonQaLines).randomOrNull()
            }

            // CYCLE phase
            val isQaTurn = WidgetPrefs.isQaTurn(context, widgetId)
            if (nonQaLines.isNotEmpty() && !isQaTurn) {
                // Plain turn
                WidgetPrefs.setIsQaTurn(context, widgetId, true)
                return nonQaLines.randomOrNull()
            }

            // Q&A turn: draw from shuffled no-repeat queue
            val qaSet = qaLines.toHashSet()
            var remaining = WidgetPrefs.getQaRemaining(context, widgetId)
                .filter { it in qaSet }
                .toMutableList()
            if (remaining.isEmpty()) remaining = qaLines.shuffled().toMutableList()

            val picked = remaining.removeFirst()
            WidgetPrefs.setQaRemaining(context, widgetId, remaining)

            if (remaining.isEmpty()) {
                // All Q&A exhausted → switch to POST
                WidgetPrefs.setPhase(context, widgetId, WidgetPrefs.PHASE_POST)
                WidgetPrefs.setIsQaTurn(context, widgetId, true)
            } else if (nonQaLines.isNotEmpty()) {
                // Schedule plain line next
                WidgetPrefs.setIsQaTurn(context, widgetId, false)
            }
            return picked
        }

        private fun parseLine(line: String?): Pair<String?, String?> {
            if (line == null) return null to null
            val parts = line.split("|", limit = 2)
            return parts[0].trim().ifBlank { null } to
                parts.getOrNull(1)?.trim()?.ifBlank { null }
        }

        private fun loadImageForWidget(context: Context): Bitmap? {
            val configs = Preferences.getWallpaperConfigs()
            val safeModeActive = Preferences.isSafeModeActive()
            val localConfig = configs.firstOrNull {
                it.source == WallpaperSource.LOCAL && (!safeModeActive || it.safeOnly)
            }

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
