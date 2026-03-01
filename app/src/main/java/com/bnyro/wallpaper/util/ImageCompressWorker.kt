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

            // Clean up old originals (> 7 days)
            cleanupOldOriginals(configs)

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
            // Move original to wallpaper_originals/
            if (!backupOriginal(context, wallpaper)) return

            // Ensure .jpg extension since we always compress to JPEG
            val rawName = file.name ?: return
            val jpgName = rawName.substringBeforeLast('.') + ".jpg"
            val parentDir = getParentDir(context, wallpaper) ?: return
            val newFile = parentDir.createFile("image/jpeg", jpgName) ?: run {
                Log.e(TAG, "Failed to create compressed file: $jpgName")
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
     * Move the original file into wallpaper_originals/ under the same root,
     * preserving the relative subdirectory structure.
     * Returns true if backup succeeded and original was deleted.
     */
    private fun backupOriginal(
        context: Context,
        wallpaper: LocalWallpaperHelper.LocalWallpaper,
    ): Boolean {
        val rootDir = wallpaper.rootDir
        val originalsDir = rootDir.findFile(LocalWallpaperHelper.ORIGINALS_DIR_NAME)
            ?: rootDir.createDirectory(LocalWallpaperHelper.ORIGINALS_DIR_NAME)
            ?: run {
                Log.e(TAG, "Failed to create ${LocalWallpaperHelper.ORIGINALS_DIR_NAME}")
                return false
            }

        var destDir = originalsDir
        for (segment in wallpaper.relativeDirSegments) {
            destDir = destDir.findFile(segment)
                ?: destDir.createDirectory(segment)
                ?: run {
                    Log.e(TAG, "Failed to create subdirectory: $segment")
                    return false
                }
        }

        val fileName = wallpaper.file.name ?: return false
        val mimeType = wallpaper.file.type ?: "application/octet-stream"
        val backupFile = destDir.createFile(mimeType, fileName) ?: run {
            Log.e(TAG, "Failed to create backup file: $fileName")
            return false
        }

        return try {
            context.contentResolver.openInputStream(wallpaper.file.uri)?.use { input ->
                context.contentResolver.openOutputStream(backupFile.uri)?.use { output ->
                    input.copyTo(output)
                }
            }
            wallpaper.file.delete()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to backup original: $fileName", e)
            runCatching { backupFile.delete() }
            false
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

    /**
     * Delete files in wallpaper_originals/ that are older than [ORIGINALS_RETAIN_DAYS] days.
     */
    private fun cleanupOldOriginals(configs: List<com.bnyro.wallpaper.obj.WallpaperConfig>) {
        val cutoff = System.currentTimeMillis() - ORIGINALS_RETAIN_DAYS * 24 * 60 * 60 * 1000L
        for (config in configs) {
            for (uriString in config.localFolderUris) {
                try {
                    val rootDir = DocumentFile.fromTreeUri(applicationContext, uriString.toUri())
                        ?: continue
                    val originalsDir = rootDir.findFile(LocalWallpaperHelper.ORIGINALS_DIR_NAME)
                        ?: continue
                    deleteOlderThan(originalsDir, cutoff)
                } catch (e: Exception) {
                    Log.w(TAG, "Cleanup failed for $uriString", e)
                }
            }
        }
    }

    private fun deleteOlderThan(dir: DocumentFile, cutoff: Long) {
        for (child in dir.listFiles()) {
            if (child.isDirectory) {
                deleteOlderThan(child, cutoff)
                // Remove empty directories
                if (child.listFiles().isEmpty()) child.delete()
            } else if (child.lastModified() < cutoff) {
                child.delete()
            }
        }
    }

    companion object {
        private const val TAG = "ImageCompressWorker"
        const val MAX_LONG_SIDE = 4800
        private const val JPEG_QUALITY = 85
        private const val ORIGINALS_RETAIN_DAYS = 7
    }
}
