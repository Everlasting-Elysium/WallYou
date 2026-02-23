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
            return Result.success()
        }

        val config = wallpaperConfigs.firstOrNull {
            it.id == configId
        } ?: return Result.success()

        val nowMillis = TimeHelper.timeTodayInMillis()
        if (config.startTimeMillis != null && config.endTimeMillis != null &&
            !TimeHelper.isInTimeRange(nowMillis, config.startTimeMillis!!, config.endTimeMillis!!)
        ) return Result.success()

        return if (runWallpaperChanger(config)) Result.success()
        else Result.retry()
    }

    /**
     * Fetch and apply a wallpaper using the given config
     *
     * @return Whether the wallpaper change applied successfully without errors
     */
    private suspend fun runWallpaperChanger(config: WallpaperConfig): Boolean {
        // For LOCAL source we need the selected file reference for the swap
        var selectedLocalWallpaper: LocalWallpaperHelper.LocalWallpaper? = null

        val bitmap = when (config.source) {
            WallpaperSource.ONLINE -> getOnlineWallpaper(config)
            WallpaperSource.FAVORITES -> getFavoritesWallpaper()
            WallpaperSource.LOCAL -> {
                val result = pickLocalWallpaper(config) ?: return false
                selectedLocalWallpaper = result.first
                result.second
            }
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

        // After successful wallpaper set, swap local file into wallpaper_used/
        if (selectedLocalWallpaper != null) {
            LocalWallpaperHelper.swapWallpaper(applicationContext, selectedLocalWallpaper)
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

    private fun pickLocalWallpaper(
        config: WallpaperConfig
    ): Pair<LocalWallpaperHelper.LocalWallpaper, Bitmap>? {
        return try {
            val wallpapers = LocalWallpaperHelper.getLocalWalls(applicationContext, config)
            if (wallpapers.isEmpty()) return null

            val keyMap = wallpapers.associateBy { LocalWallpaperHelper.toStableKey(it) }
            val pickedKey = ShuffleQueue.pickNext(
                applicationContext,
                "wallpaper_config_${config.id}",
                keyMap.keys
            ) ?: return null

            val selected = keyMap[pickedKey] ?: return null
            val bitmap = ImageHelper.getLocalImage(applicationContext, selected.file.uri)
                ?: return null
            selected to bitmap
        } catch (e: Exception) {
            Log.e(this@BackgroundWorker::class.simpleName, e.toString())
            null
        }
    }
}
