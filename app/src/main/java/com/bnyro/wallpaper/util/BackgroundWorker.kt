package com.bnyro.wallpaper.util

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.bnyro.wallpaper.db.DatabaseHolder
import com.bnyro.wallpaper.db.obj.Wallpaper
import com.bnyro.wallpaper.enums.WallpaperSource
import com.bnyro.wallpaper.obj.WallpaperConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BackgroundWorker(
    applicationContext: Context,
    private val workerParameters: WorkerParameters
) : CoroutineWorker(applicationContext, workerParameters) {
    override suspend fun doWork(): Result {
        val wallpaperConfigs = Preferences.getWallpaperConfigs()

        val configId = workerParameters.inputData.getInt(WorkerHelper.WALLPAPER_CONFIG_ID, -1)
        if (configId == -1) {
            for (config in wallpaperConfigs) {
                runWallpaperChanger(config)
            }
            LocalWallpaperHelper.cleanupOldOriginals(applicationContext)
            return Result.success()
        }

        val config = wallpaperConfigs.firstOrNull {
            it.id == configId
        } ?: return Result.success()

        val nowMillis = TimeHelper.timeTodayInMillis()
        if (config.startTimeMillis != null && config.endTimeMillis != null &&
            !TimeHelper.isInTimeRange(nowMillis, config.startTimeMillis!!, config.endTimeMillis!!)
        ) return Result.success()

        val changerResult = if (runWallpaperChanger(config)) Result.success()
        else Result.retry()
        LocalWallpaperHelper.cleanupOldOriginals(applicationContext)
        return changerResult
    }

    /**
     * Fetch and apply a wallpaper using the given config
     *
     * @return Whether the wallpaper change applied successfully without errors
     */
    private suspend fun runWallpaperChanger(config: WallpaperConfig): Boolean {
        val bitmap = when (config.source) {
            WallpaperSource.ONLINE -> getOnlineWallpaper(config)
            WallpaperSource.FAVORITES -> getFavoritesWallpaper()
            WallpaperSource.LOCAL -> pickLocalWallpaper(config)
        } ?: return false

        if (config.applyImageFilters) {
            WallpaperHelper.setWallpaperWithFilters(
                applicationContext,
                bitmap,
                config.target
            )
        } else {
            WallpaperHelper.setWallpaperWithoutFilters(
                applicationContext,
                bitmap,
                config.target
            )
        }

        return true
    }

    private suspend fun getOnlineWallpaper(config: WallpaperConfig): Bitmap? {
        val source = config.selectedApiRoutes.ifEmpty { return null }.random()

        return withContext(Dispatchers.IO) {
            val url = try {
                Preferences.getApiByRoute(source).getRandomWallpaperUrl()
            } catch (e: Exception) {
                Log.e(this@BackgroundWorker::class.simpleName, e.toString())
                return@withContext null
            } ?: return@withContext null

            val bitmap = ImageHelper.urlToBitmap(url, applicationContext, forceReload = true)
            if (bitmap != null && Preferences.getBoolean(Preferences.wallpaperHistory, true)) {
                val wallpaper = Wallpaper(imgSrc = url)
                DatabaseHolder.Database.favoritesDao().insert(wallpaper, null, true)
            }

            bitmap
        }
    }

    private suspend fun getFavoritesWallpaper(): Bitmap? {
        val favoriteUrl = withContext(Dispatchers.IO) {
            DatabaseHolder.Database.favoritesDao().getRandomFavorite()
        }?.imgSrc
        return ImageHelper.urlToBitmap(favoriteUrl, applicationContext, forceReload = true)
    }

    private fun pickLocalWallpaper(config: WallpaperConfig): Bitmap? {
        return try {
            val wallpapers = LocalWallpaperHelper.getLocalWalls(applicationContext, config)
            if (wallpapers.isEmpty()) return null

            val keyMap = wallpapers.associateBy { LocalWallpaperHelper.toStableKey(it) }
            val queueName = "wallpaper_config_${config.id}"
            val pickedKey = ShuffleQueue.pickNext(
                applicationContext,
                queueName,
                keyMap.keys
            ) ?: return null

            val selected = keyMap[pickedKey] ?: return null

            // Rename + compress if needed (backs up original, returns final URI and new key)
            val result = LocalWallpaperHelper.processWallpaper(applicationContext, selected)

            // Sync ShuffleQueue if key changed (file was renamed)
            if (result.newKey != pickedKey) {
                ShuffleQueue.replaceKey(applicationContext, queueName, pickedKey, result.newKey)
            }

            // Record current wallpaper metadata for tile actions
            Preferences.setCurrentWallpaper(
                key = result.newKey,
                uri = result.uri.toString(),
                folderUri = selected.rootDir.uri.toString()
            )

            ImageHelper.getLocalImage(applicationContext, result.uri)
        } catch (e: Exception) {
            Log.e(this@BackgroundWorker::class.simpleName, e.toString())
            null
        }
    }
}
