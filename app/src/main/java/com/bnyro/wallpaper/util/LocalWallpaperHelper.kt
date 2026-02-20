package com.bnyro.wallpaper.util

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.bnyro.wallpaper.obj.WallpaperConfig

object LocalWallpaperHelper {
    private const val TAG = "LocalWallpaperHelper"
    const val USED_DIR_NAME = "wallpaper_used"

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
     * subdirectory path from root. Skips hidden dirs and [USED_DIR_NAME].
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
                    it.name != USED_DIR_NAME
        }.forEach { subDir ->
            results.addAll(
                collectFiles(subDir, relativeDirSegments + subDir.name.orEmpty())
            )
        }

        return results
    }

    private val DocumentFile.isImage get() = type?.startsWith("image/") ?: false

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

    // ── Swap: move new wallpaper in, restore previous one out ────

    /**
     * 1. Restore any file currently in wallpaper_used/ back to its
     *    original location (preserving subdirectory structure).
     * 2. Move [selected] into wallpaper_used/ (preserving subdirectory
     *    structure).
     */
    fun swapWallpaper(context: Context, selected: LocalWallpaper) {
        val rootDir = selected.rootDir

        // Step 1 – restore previous wallpaper from wallpaper_used/ back to source
        val usedDir = rootDir.findFile(USED_DIR_NAME)
        if (usedDir != null) {
            restoreFromUsedDir(context, rootDir, usedDir)
        }

        // Step 2 – move newly-selected wallpaper into wallpaper_used/
        val targetUsedDir = rootDir.findFile(USED_DIR_NAME)
            ?: rootDir.createDirectory(USED_DIR_NAME)
            ?: run {
                Log.e(TAG, "Failed to create $USED_DIR_NAME directory")
                return
            }

        val destDir = ensureSubDirs(targetUsedDir, selected.relativeDirSegments) ?: run {
            Log.e(TAG, "Failed to create subdirs in $USED_DIR_NAME")
            return
        }

        moveFile(context, selected.file, destDir)
    }

    // ── Restore ─────────────────────────────────────────────────────

    /**
     * Move every file inside [usedDir] back to [rootDir], mirroring
     * the subdirectory structure. Then clean up empty subdirectories.
     */
    private fun restoreFromUsedDir(
        context: Context,
        rootDir: DocumentFile,
        usedDir: DocumentFile,
    ) {
        val files = collectFilesInDir(usedDir)
        for ((file, relSegments) in files) {
            val targetDir = ensureSubDirs(rootDir, relSegments) ?: continue
            moveFile(context, file, targetDir)
        }
        cleanEmptyDirs(usedDir)
    }

    /**
     * Recursively list all files inside [directory] together with their
     * relative path segments. Unlike [collectFiles], this does NOT skip
     * any directory names – it's used for the wallpaper_used tree.
     */
    private fun collectFilesInDir(
        directory: DocumentFile,
        relativeDirSegments: List<String> = emptyList(),
    ): List<Pair<DocumentFile, List<String>>> {
        val results = mutableListOf<Pair<DocumentFile, List<String>>>()
        val contents = directory.listFiles()

        results.addAll(contents.filter { it.isFile }.map { it to relativeDirSegments })

        contents.filter { it.isDirectory }.forEach { subDir ->
            results.addAll(
                collectFilesInDir(subDir, relativeDirSegments + subDir.name.orEmpty())
            )
        }

        return results
    }

    // ── Helpers ──────────────────────────────────────────────────────

    /**
     * Ensure the chain of subdirectories exists under [root].
     * Returns the deepest directory, or null on failure.
     */
    private fun ensureSubDirs(root: DocumentFile, segments: List<String>): DocumentFile? {
        var current = root
        for (segment in segments) {
            current = current.findFile(segment)
                ?: current.createDirectory(segment)
                ?: return null
        }
        return current
    }

    /**
     * Copy [file] into [destDir], then delete the original.
     * Uses SAF streams so it works across any document provider.
     */
    private fun moveFile(context: Context, file: DocumentFile, destDir: DocumentFile) {
        val fileName = file.name ?: return
        val mimeType = file.type ?: "application/octet-stream"

        val newFile = destDir.createFile(mimeType, fileName) ?: run {
            Log.e(TAG, "Failed to create file $fileName in destination")
            return
        }

        try {
            context.contentResolver.openInputStream(file.uri)?.use { input ->
                context.contentResolver.openOutputStream(newFile.uri)?.use { output ->
                    input.copyTo(output)
                }
            }
            file.delete()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to move file $fileName", e)
            // Clean up the partially-created destination file
            runCatching { newFile.delete() }
        }
    }

    /**
     * Recursively delete empty subdirectories inside [dir].
     * Does NOT delete [dir] itself.
     */
    private fun cleanEmptyDirs(dir: DocumentFile) {
        dir.listFiles().filter { it.isDirectory }.forEach { subDir ->
            cleanEmptyDirs(subDir)
            if (subDir.listFiles().isEmpty()) {
                subDir.delete()
            }
        }
    }
}
