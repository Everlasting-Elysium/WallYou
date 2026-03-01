package com.bnyro.wallpaper.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.bnyro.wallpaper.enums.WallpaperSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ImageCompressWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val configs = Preferences.getWallpaperConfigs()
                .filter { it.source == WallpaperSource.LOCAL }

            for (config in configs) {
                val wallpapers = LocalWallpaperHelper.getLocalWalls(applicationContext, config)
                for (wallpaper in wallpapers) {
                    compressIfNeeded(wallpaper)
                }
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Compress worker failed", e)
            Result.failure()
        }
    }

    private fun compressIfNeeded(wallpaper: LocalWallpaperHelper.LocalWallpaper) {
        val context = applicationContext
        val file = wallpaper.file

        // Read dimensions only (no pixel data loaded)
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(file.uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, opts)
        }

        val origWidth = opts.outWidth
        val origHeight = opts.outHeight
        if (origWidth <= 0 || origHeight <= 0) return

        val longSide = maxOf(origWidth, origHeight)
        if (longSide <= MAX_LONG_SIDE) return

        Log.d(TAG, "Compressing ${file.name}: ${origWidth}x${origHeight}")

        // Calculate inSampleSize (power of 2) for memory-efficient decoding
        var inSampleSize = 1
        var halfLong = longSide / 2
        while (halfLong / inSampleSize >= MAX_LONG_SIDE) {
            inSampleSize *= 2
            halfLong = longSide / (inSampleSize * 2)
        }

        val decodeOpts = BitmapFactory.Options().apply { this.inSampleSize = inSampleSize }
        val sampledBitmap = context.contentResolver.openInputStream(file.uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOpts)
        } ?: return

        // Scale to exact target dimensions
        val scale = MAX_LONG_SIDE.toFloat() / maxOf(sampledBitmap.width, sampledBitmap.height)
        val targetWidth = (sampledBitmap.width * scale).toInt()
        val targetHeight = (sampledBitmap.height * scale).toInt()

        val scaledBitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(sampledBitmap, targetWidth, targetHeight, true).also {
                if (it !== sampledBitmap) sampledBitmap.recycle()
            }
        } else {
            sampledBitmap
        }

        try {
            // Move original to .wallpaper_originals/
            backupOriginal(context, wallpaper)

            // Write compressed image to original location
            val fileName = file.name ?: return
            val parentDir = getParentDir(context, wallpaper) ?: return
            val newFile = parentDir.createFile("image/jpeg", fileName) ?: run {
                Log.e(TAG, "Failed to create compressed file: $fileName")
                return
            }

            context.contentResolver.openOutputStream(newFile.uri)?.use { out ->
                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
        } finally {
            scaledBitmap.recycle()
        }

        Log.d(TAG, "Compressed ${file.name}: ${origWidth}x${origHeight} -> ${targetWidth}x${targetHeight}")
    }

    /**
     * Move the original file into .wallpaper_originals/ under the same root,
     * preserving the relative subdirectory structure.
     */
    private fun backupOriginal(
        context: Context,
        wallpaper: LocalWallpaperHelper.LocalWallpaper,
    ) {
        val rootDir = wallpaper.rootDir
        val originalsDir = rootDir.findFile(LocalWallpaperHelper.ORIGINALS_DIR_NAME)
            ?: rootDir.createDirectory(LocalWallpaperHelper.ORIGINALS_DIR_NAME)
            ?: run {
                Log.e(TAG, "Failed to create ${LocalWallpaperHelper.ORIGINALS_DIR_NAME}")
                return
            }

        var destDir = originalsDir
        for (segment in wallpaper.relativeDirSegments) {
            destDir = destDir.findFile(segment)
                ?: destDir.createDirectory(segment)
                ?: run {
                    Log.e(TAG, "Failed to create subdirectory: $segment")
                    return
                }
        }

        val fileName = wallpaper.file.name ?: return
        val mimeType = wallpaper.file.type ?: "application/octet-stream"
        val backupFile = destDir.createFile(mimeType, fileName) ?: run {
            Log.e(TAG, "Failed to create backup file: $fileName")
            return
        }

        try {
            context.contentResolver.openInputStream(wallpaper.file.uri)?.use { input ->
                context.contentResolver.openOutputStream(backupFile.uri)?.use { output ->
                    input.copyTo(output)
                }
            }
            wallpaper.file.delete()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to backup original: $fileName", e)
            runCatching { backupFile.delete() }
        }
    }

    /**
     * Get the parent directory for a wallpaper file, creating subdirectories if needed.
     */
    private fun getParentDir(
        context: Context,
        wallpaper: LocalWallpaperHelper.LocalWallpaper,
    ): DocumentFile? {
        var dir = wallpaper.rootDir
        for (segment in wallpaper.relativeDirSegments) {
            dir = dir.findFile(segment)
                ?: dir.createDirectory(segment)
                ?: return null
        }
        return dir
    }

    companion object {
        private const val TAG = "ImageCompressWorker"
        const val MAX_LONG_SIDE = 4800
        private const val JPEG_QUALITY = 85
    }
}
