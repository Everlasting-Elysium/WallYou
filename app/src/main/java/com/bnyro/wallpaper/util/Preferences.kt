package com.bnyro.wallpaper.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.bnyro.wallpaper.App
import com.bnyro.wallpaper.obj.WallpaperConfig
import net.youapps.wallpaper_apis.RetrofitHelper

object Preferences {
    const val resizeMethodKey = "resizeMethod"
    const val diskCacheKey = "diskCache"
    const val themeModeKey = "themeModeKey"
    const val wallpaperHistory = "autoAddToFavorites"
    const val grayscaleKey = "grayscale"
    const val blurKey = "blur"
    const val startTabKey = "startTab"
    const val invertKey = "invert"
    const val contrastKey = "contrast"
    const val hueKey = "hue"
    const val brightnessKey = "brightness"
    const val autoLightenDarkenKey = "autoLightenDarken"

    const val safeModeActiveKey = "safeModeActive"
    const val safeModeScheduleEnabledKey = "safeModeScheduleEnabled"
    const val safeModeScheduleStartKey = "safeModeScheduleStart"
    const val safeModeScheduleEndKey = "safeModeScheduleEnd"

    private const val currentWallpaperKeyPref = "current_wallpaper_key"
    private const val currentWallpaperUriPref = "current_wallpaper_uri"
    private const val currentWallpaperFolderUriPref = "current_wallpaper_folder_uri"

    const val wallpaperChangerKey = "wallpaperChanger"
    private const val wallpaperChangerConfigKey = "wallpaperChangerConfigurations"

    const val defaultDiskCacheSize = 128L * 1024 * 1024
    const val defaultWallpaperChangeInterval = 12L * 60

    private const val prefFile = "preferences"
    private lateinit var preferences: SharedPreferences

    fun init(context: Context) {
        preferences = context.getSharedPreferences(prefFile, Context.MODE_PRIVATE)
    }

    fun getBoolean(key: String, defValue: Boolean) = preferences.getBoolean(key, defValue)
    fun getString(key: String, defValue: String) = preferences.getString(key, defValue) ?: defValue

    fun getFloat(key: String, defValue: Float) = preferences.getFloat(key, defValue)
    fun getLong(key: String, defValue: Long) = preferences.getLong(key, defValue)

    fun edit(action: SharedPreferences.Editor.() -> Unit) {
        preferences.edit().apply(action).apply()
    }

    fun getApiByRoute(route: String) = App.apis.firstOrNull { it.route == route } ?: App.apis.first()

    fun setWallpaperConfigs(configs: List<WallpaperConfig>) {
        edit { putString(wallpaperChangerConfigKey, RetrofitHelper.json.encodeToString(configs)) }
    }

    fun getWallpaperConfigs(): List<WallpaperConfig> {
        val prefString = getString(wallpaperChangerConfigKey, "")

        if (prefString.isEmpty()) return emptyList()

        return try {
            RetrofitHelper.json.decodeFromString<List<WallpaperConfig>>(prefString)
        } catch (e: Exception) {
            Log.e(this.javaClass.name, e.stackTraceToString())
            listOf()
        }
    }

    fun setCurrentWallpaper(key: String?, uri: String?, folderUri: String?) {
        edit {
            putString(currentWallpaperKeyPref, key)
            putString(currentWallpaperUriPref, uri)
            putString(currentWallpaperFolderUriPref, folderUri)
        }
    }

    fun isSafeModeActive(): Boolean {
        if (getBoolean(safeModeActiveKey, false)) return true
        if (!getBoolean(safeModeScheduleEnabledKey, false)) return false
        val start = getLong(safeModeScheduleStartKey, -1)
        val end = getLong(safeModeScheduleEndKey, -1)
        if (start < 0 || end < 0) return false
        return TimeHelper.isInTimeRange(TimeHelper.timeTodayInMillis(), start, end)
    }

    fun getCurrentWallpaperKey(): String? = preferences.getString(currentWallpaperKeyPref, null)
    fun getCurrentWallpaperUri(): String? = preferences.getString(currentWallpaperUriPref, null)
    fun getCurrentWallpaperFolderUri(): String? = preferences.getString(currentWallpaperFolderUriPref, null)

    private fun lastLocalStableIdKey(configId: Int) = "last_local_stable_id_$configId"
    private const val lastGlobalLocalStableIdKey = "last_global_local_stable_id"

    fun getLastLocalStableId(configId: Int): String? =
        preferences.getString(lastLocalStableIdKey(configId), null)

    fun setLastLocalStableId(configId: Int, id: String) =
        edit { putString(lastLocalStableIdKey(configId), id) }

    fun getLastGlobalLocalStableId(): String? =
        preferences.getString(lastGlobalLocalStableIdKey, null)

    fun setLastGlobalLocalStableId(id: String) =
        edit { putString(lastGlobalLocalStableIdKey, id) }
}
