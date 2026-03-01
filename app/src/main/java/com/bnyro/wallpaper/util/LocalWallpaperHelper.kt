package com.bnyro.wallpaper.util

import android.content.Context
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.bnyro.wallpaper.obj.WallpaperConfig

object LocalWallpaperHelper {
    /** Legacy directory name – still skipped during scanning for backward compat. */
    private const val USED_DIR_NAME = "wallpaper_used"

    /** Directory used by ImageCompressWorker to hold original (pre-compression) files. */
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
}
