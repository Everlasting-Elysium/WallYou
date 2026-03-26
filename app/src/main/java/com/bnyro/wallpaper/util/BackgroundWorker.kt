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
        if (config.source == WallpaperSource.LOCAL) {
            return runLocalChanger(config)
        }

        val bitmap = when (config.source) {
            WallpaperSource.ONLINE -> getOnlineWallpaper(config)
            WallpaperSource.FAVORITES -> getFavoritesWallpaper()
            else -> null
        } ?: return false

        applyWallpaper(config, bitmap)
        return true
    }

    private suspend fun applyWallpaper(config: WallpaperConfig, bitmap: Bitmap) {
        if (config.applyImageFilters) {
            WallpaperHelper.setWallpaperWithFilters(applicationContext, bitmap, config.target)
        } else {
            WallpaperHelper.setWallpaperWithoutFilters(applicationContext, bitmap, config.target)
        }
    }

    /**
     * LOCAL wallpaper flow: pick from unused pool → load bitmap → set wallpaper
     * → rename/compress with _used marker in one step.
     * When the pool is empty, all "_used" markers are removed to reset the cycle.
     */
    private suspend fun runLocalChanger(config: WallpaperConfig): Boolean {
        return try {
            var wallpapers = LocalWallpaperHelper.getLocalWalls(applicationContext, config)

            // All wallpapers have been used – clear _used markers and rescan
            if (wallpapers.isEmpty()) {
                LocalWallpaperHelper.resetUsedMarkers(applicationContext, config)
                wallpapers = LocalWallpaperHelper.getLocalWalls(applicationContext, config)
                if (wallpapers.isEmpty()) return false
            }

            val selected = wallpapers.random()

            // 1. Load bitmap from ORIGINAL file (no file changes yet)
            val bitmap = ImageHelper.getLocalImage(applicationContext, selected.file.uri)
                ?: return false

            // 2. Set wallpaper — if this fails, original file is untouched
            applyWallpaper(config, bitmap)

            // 3. Only after success: rename/compress and mark as used in one step
            val result = LocalWallpaperHelper.processWallpaper(applicationContext, selected)

            // 4. Record metadata for tile actions
            Preferences.setCurrentWallpaper(
                key = result.newKey,
                uri = result.uri.toString(),
                folderUri = selected.rootDir.uri.toString()
            )

            true
        } catch (e: Exception) {
            Log.e(this@BackgroundWorker::class.simpleName, e.toString())
            false
        }
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
}
