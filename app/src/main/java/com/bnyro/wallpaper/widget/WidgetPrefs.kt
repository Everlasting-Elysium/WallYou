package com.bnyro.wallpaper.widget

import android.content.Context

object WidgetPrefs {
    private const val PREFS_NAME = "widget_prefs"

    private const val KEY_MODE = "mode_"
    private const val KEY_TXT_URI = "txt_uri_"
    private const val KEY_REFRESH_MIN = "refresh_min_"
    private const val KEY_CURRENT_LINE = "current_line_"

    const val MODE_TRANSPARENT = 0
    const val MODE_TEXT = 1
    const val MODE_IMAGE = 2

    const val DEFAULT_REFRESH_MIN = 5
    const val MIN_REFRESH_MIN = 1

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getMode(context: Context, appWidgetId: Int): Int =
        prefs(context).getInt(KEY_MODE + appWidgetId, MODE_TRANSPARENT)

    fun setMode(context: Context, appWidgetId: Int, mode: Int) {
        prefs(context).edit().putInt(KEY_MODE + appWidgetId, mode).apply()
    }

    fun getTxtUri(context: Context, appWidgetId: Int): String? =
        prefs(context).getString(KEY_TXT_URI + appWidgetId, null)

    fun setTxtUri(context: Context, appWidgetId: Int, uri: String?) {
        prefs(context).edit().putString(KEY_TXT_URI + appWidgetId, uri).apply()
    }

    fun getCurrentLine(context: Context, appWidgetId: Int): String? =
        prefs(context).getString(KEY_CURRENT_LINE + appWidgetId, null)

    fun setCurrentLine(context: Context, appWidgetId: Int, line: String?) {
        prefs(context).edit().putString(KEY_CURRENT_LINE + appWidgetId, line).apply()
    }

    fun getRefreshMinutes(context: Context, appWidgetId: Int): Int =
        prefs(context).getInt(KEY_REFRESH_MIN + appWidgetId, DEFAULT_REFRESH_MIN)

    fun setRefreshMinutes(context: Context, appWidgetId: Int, minutes: Int) {
        prefs(context).edit().putInt(KEY_REFRESH_MIN + appWidgetId, minutes.coerceAtLeast(MIN_REFRESH_MIN)).apply()
    }

    fun remove(context: Context, appWidgetId: Int) {
        prefs(context).edit()
            .remove(KEY_MODE + appWidgetId)
            .remove(KEY_TXT_URI + appWidgetId)
            .remove(KEY_REFRESH_MIN + appWidgetId)
            .remove(KEY_CURRENT_LINE + appWidgetId)
            .apply()
    }
}
