package com.bnyro.wallpaper.util

import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.net.toUri
import androidx.core.os.postDelayed
import androidx.documentfile.provider.DocumentFile
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

@RequiresApi(Build.VERSION_CODES.N)
class MoveWallpaperTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile.state = Tile.STATE_INACTIVE
        qsTile.updateTile()
    }

    override fun onClick() {
        super.onClick()

        qsTile.state = Tile.STATE_ACTIVE
        qsTile.updateTile()

        Thread {
            val moved = moveCurrentWallpaperToSafeFolder()

            if (moved) {
                Preferences.setCurrentWallpaper(null, null, null)

                val oneTimeJob = OneTimeWorkRequestBuilder<BackgroundWorker>().build()
                WorkManager.getInstance(this)
                    .enqueueUniqueWork(TILE_WORKER_KEY, ExistingWorkPolicy.REPLACE, oneTimeJob)
            }

            Handler(Looper.getMainLooper()).post {
                qsTile.state = if (moved) Tile.STATE_ACTIVE else Tile.STATE_UNAVAILABLE
                qsTile.updateTile()
            }

            Handler(Looper.getMainLooper()).postDelayed(3000) {
                qsTile.state = Tile.STATE_INACTIVE
                qsTile.updateTile()
            }
        }.start()
    }

    private fun moveCurrentWallpaperToSafeFolder(): Boolean {
        val uriString = Preferences.getCurrentWallpaperUri() ?: return false
        val fileUri = uriString.toUri()

        val safeFolderUris = Preferences.getWallpaperConfigs()
            .filter { it.safeOnly && it.localFolderUris.isNotEmpty() }
            .flatMap { it.localFolderUris }
        val safeFolderUri = safeFolderUris.firstOrNull() ?: return false

        // Check if the current wallpaper is already inside a safe folder.
        // Use SAF document IDs (path-like) so it works even when
        // getCurrentWallpaperFolderUri() is null (wallpaper set manually).
        if (isDocumentUnderAnyTree(fileUri, safeFolderUris)) return false

        return try {
            val sourceFile = DocumentFile.fromSingleUri(this, fileUri) ?: return false
            val destDir = DocumentFile.fromTreeUri(this, safeFolderUri.toUri()) ?: return false

            val fileName = sourceFile.name ?: "wallpaper_${System.currentTimeMillis()}"
            val mimeType = sourceFile.type ?: "image/*"

            destDir.findFile(fileName)?.delete()
            val destFile = destDir.createFile(mimeType, fileName.substringBeforeLast('.'))
                ?: return false

            contentResolver.openInputStream(sourceFile.uri)?.use { input ->
                contentResolver.openOutputStream(destFile.uri)?.use { output ->
                    input.copyTo(output)
                }
            }

            sourceFile.delete()
            Log.d(TAG, "Moved $fileName to safe folder")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to move wallpaper to safe folder", e)
            false
        }
    }

    private fun isDocumentUnderAnyTree(fileUri: Uri, treeUriStrings: List<String>): Boolean {
        val fileDocId = runCatching { DocumentsContract.getDocumentId(fileUri) }.getOrNull()
            ?: return false

        for (treeUriString in treeUriStrings) {
            val treeDocId = runCatching {
                DocumentsContract.getTreeDocumentId(treeUriString.toUri())
            }.getOrNull() ?: continue

            if (fileDocId == treeDocId || fileDocId.startsWith("$treeDocId/")) return true
        }
        return false
    }

    companion object {
        private const val TAG = "MoveWallpaperTile"
        private const val TILE_WORKER_KEY = "move_wallpaper_tile_worker"
    }
}
