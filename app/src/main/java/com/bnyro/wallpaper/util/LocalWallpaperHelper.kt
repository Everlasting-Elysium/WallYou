package com.bnyro.wallpaper.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.bnyro.wallpaper.obj.WallpaperConfig
import com.bnyro.wallpaper.enums.WallpaperSource

object LocalWallpaperHelper {
    /** Legacy directory name – still skipped during scanning for backward compat. */
    private const val USED_DIR_NAME = "wallpaper_used"

    /** Directory used to hold original (pre-compression) files. */
    const val ORIGINALS_DIR_NAME = "wallpaper_originals"

    /** Directory used by DeleteWallpaperTileService as a recycle bin. */
    const val TRASH_DIR_NAME = "wallpaper_trash"

    /**
     * Holds a local wallpaper file together with its root directory and
     * the relative subdirectory path from root to the file's parent.
     *
     * e.g. for root/nature/mountain.jpg:
     *   file = DocumentFile(mountain.jpg)
     *   rootDir = DocumentFile(root)
     *   relativeDirSegments = ["nature"]
     */
    data class LocalWallpaper(
        val file: DocumentFile,
        val rootDir: DocumentFile,
        val relativeDirSegments: List<String>,
    )

    // ── Scanning ────────────────────────────────────────────────────

    /**
     * Recursively collect files under [directory], tracking the relative
     * subdirectory path from root. Skips hidden dirs, reserved dirs
     * (wallpaper_originals, wallpaper_trash) and the legacy [USED_DIR_NAME].
     */
    private fun collectFiles(
        directory: DocumentFile?,
        relativeDirSegments: List<String> = emptyList(),
    ): List<Pair<DocumentFile, List<String>>> {
        val results = mutableListOf<Pair<DocumentFile, List<String>>>()
        val contents = directory?.listFiles().orEmpty()

        results.addAll(contents.filter { it.isFile }.map { it to relativeDirSegments })

        contents.filter {
            it.isDirectory &&
                    !it.name.orEmpty().startsWith(".") &&
                    it.name != USED_DIR_NAME && it.name != TRASH_DIR_NAME && it.name != ORIGINALS_DIR_NAME
        }.forEach { subDir ->
            results.addAll(
                collectFiles(subDir, relativeDirSegments + subDir.name.orEmpty())
            )
        }

        return results
    }

    private val DocumentFile.isImage get() = type?.startsWith("image/") ?: false

    fun toStableKey(wallpaper: LocalWallpaper): String {
        val segments = wallpaper.relativeDirSegments + (wallpaper.file.name ?: "")
        return segments.joinToString("/")
    }

    fun getLocalWalls(context: Context, config: WallpaperConfig): List<LocalWallpaper> {
        return config.localFolderUris.mapNotNull { uriString ->
            val dir = DocumentFile.fromTreeUri(context, uriString.toUri())
                ?: return@mapNotNull null
            collectFiles(dir).map { (file, segments) ->
                LocalWallpaper(file, dir, segments)
            }
        }
            .flatten()
            .filter { it.file.isImage }
    }

    // ── On-demand rename + compression ──────────────────────────────

    private const val TAG = "LocalWallpaperHelper"
    const val MAX_LONG_SIDE = 4800
    private const val JPEG_QUALITY = 85
    private const val ORIGINALS_RETAIN_DAYS = 7
    private const val COMPRESSED_SUFFIX = "_compressed"

    data class ProcessResult(
        val uri: Uri,
        val newKey: String,
    )

    /**
     * Rename the wallpaper file to the current timestamp.
     * If the long side exceeds [MAX_LONG_SIDE], also compress it
     * (backing up the original to wallpaper_originals/).
     *
     * @return [ProcessResult] with the final URI and the new stable key.
     */
    fun processWallpaper(context: Context, wallpaper: LocalWallpaper): ProcessResult {
        val file = wallpaper.file
        val oldKey = toStableKey(wallpaper)
        val rawName = file.name ?: return ProcessResult(file.uri, oldKey)
        val timestamp = System.currentTimeMillis()

        // Read dimensions to decide whether compression is needed
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(file.uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, opts)
        }

        val origWidth = opts.outWidth
        val origHeight = opts.outHeight
        val longSide = if (origWidth > 0 && origHeight > 0) maxOf(origWidth, origHeight) else 0
        val needsCompress = longSide > MAX_LONG_SIDE

        return if (needsCompress) {
            compressAndRename(context, wallpaper, rawName, timestamp, origWidth, origHeight, longSide)
        } else {
            renameOnly(context, wallpaper, rawName, timestamp)
        }
    }

    /**
     * Rename the file to {timestamp}.{ext} without compressing.
     */
    private fun renameOnly(
        context: Context,
        wallpaper: LocalWallpaper,
        rawName: String,
        timestamp: Long,
    ): ProcessResult {
        val oldKey = toStableKey(wallpaper)
        val ext = rawName.substringAfterLast('.', "")
        val newFileName = if (ext.isNotEmpty()) "${timestamp}.${ext}" else "$timestamp"
        val mimeType = wallpaper.file.type ?: "application/octet-stream"
        val parentDir = getParentDir(wallpaper) ?: return ProcessResult(wallpaper.file.uri, oldKey)

        // Delete any leftover with same name to avoid SAF duplicates
        parentDir.findFile(newFileName)?.delete()

        val newFile = parentDir.createFile(mimeType, newFileName.substringBeforeLast('.')) ?: run {
            Log.w(TAG, "Failed to create renamed file: $newFileName")
            return ProcessResult(wallpaper.file.uri, oldKey)
        }

        return try {
            context.contentResolver.openInputStream(wallpaper.file.uri)?.use { input ->
                context.contentResolver.openOutputStream(newFile.uri)?.use { output ->
                    input.copyTo(output)
                }
            }
            wallpaper.file.delete()

            val newKey = (wallpaper.relativeDirSegments + newFileName).joinToString("/")
            Log.d(TAG, "Renamed $rawName -> $newFileName")
            ProcessResult(newFile.uri, newKey)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to rename $rawName -> $newFileName", e)
            runCatching { newFile.delete() }
            ProcessResult(wallpaper.file.uri, oldKey)
        }
    }

    /**
     * Backup the original, compress, and write to {timestamp}_compressed.jpg.
     */
    private fun compressAndRename(
        context: Context,
        wallpaper: LocalWallpaper,
        rawName: String,
        timestamp: Long,
        origWidth: Int,
        origHeight: Int,
        longSide: Int,
    ): ProcessResult {
        val oldKey = toStableKey(wallpaper)
        val file = wallpaper.file

        Log.d(TAG, "Compressing ${rawName}: ${origWidth}x${origHeight}")

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
        } ?: return ProcessResult(file.uri, oldKey)

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
            // Backup original to wallpaper_originals/
            if (!backupOriginal(context, wallpaper)) return ProcessResult(file.uri, oldKey)

            val displayName = "${timestamp}${COMPRESSED_SUFFIX}"
            val parentDir = getParentDir(wallpaper) ?: return ProcessResult(file.uri, oldKey)

            // Delete any leftover with same name to avoid SAF duplicates
            parentDir.findFile(rawName)?.delete()
            val jpgName = "${displayName}.jpg"
            parentDir.findFile(jpgName)?.delete()

            val newFile = parentDir.createFile("image/jpeg", displayName) ?: run {
                Log.e(TAG, "Failed to create compressed file: $displayName")
                return ProcessResult(file.uri, oldKey)
            }

            context.contentResolver.openOutputStream(newFile.uri)?.use { out ->
                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }

            val newKey = (wallpaper.relativeDirSegments + jpgName).joinToString("/")
            Log.d(TAG, "Compressed $rawName: ${origWidth}x${origHeight} -> ${targetWidth}x${targetHeight}, key=$newKey")
            return ProcessResult(newFile.uri, newKey)
        } finally {
            scaledBitmap.recycle()
        }
    }

    /**
     * Move the original file into wallpaper_originals/ under the same root,
     * preserving the relative subdirectory structure.
     * Returns true if backup succeeded and original was deleted.
     */
    private fun backupOriginal(
        context: Context,
        wallpaper: LocalWallpaper,
    ): Boolean {
        val rootDir = wallpaper.rootDir
        val originalsDir = rootDir.findFile(ORIGINALS_DIR_NAME)
            ?: rootDir.createDirectory(ORIGINALS_DIR_NAME)
            ?: run {
                Log.e(TAG, "Failed to create $ORIGINALS_DIR_NAME")
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

        // Delete existing backup to avoid SAF creating "photo (1).png"
        destDir.findFile(fileName)?.delete()

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
     * Get the parent directory for a wallpaper file by navigating relative segments.
     */
    private fun getParentDir(wallpaper: LocalWallpaper): DocumentFile? {
        var dir = wallpaper.rootDir
        for (segment in wallpaper.relativeDirSegments) {
            dir = dir.findFile(segment)
                ?: dir.createDirectory(segment)
                ?: return null
        }
        return dir
    }

    // ── Originals cleanup ───────────────────────────────────────────

    /**
     * Delete files in wallpaper_originals/ older than [ORIGINALS_RETAIN_DAYS] days.
     */
    fun cleanupOldOriginals(context: Context) {
        val cutoff = System.currentTimeMillis() - ORIGINALS_RETAIN_DAYS * 24 * 60 * 60 * 1000L
        val configs = Preferences.getWallpaperConfigs()
            .filter { it.source == WallpaperSource.LOCAL }

        for (config in configs) {
            for (uriString in config.localFolderUris) {
                try {
                    val rootDir = DocumentFile.fromTreeUri(context, uriString.toUri())
                        ?: continue
                    val originalsDir = rootDir.findFile(ORIGINALS_DIR_NAME)
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
                if (child.listFiles().isEmpty()) child.delete()
            } else if (child.lastModified() < cutoff) {
                child.delete()
            }
        }
    }
}
