package com.bnyro.wallpaper.widget

import android.content.Context

object WidgetPrefs {
    private const val PREFS_NAME = "widget_prefs"

    private const val KEY_MODE = "mode_"
    private const val KEY_TXT_URI = "txt_uri_"
    private const val KEY_REFRESH_MIN = "refresh_min_"
    private const val KEY_CURRENT_LINE = "current_line_"
    private const val KEY_CURRENT_ANSWER = "current_answer_"
    private const val KEY_ANSWER_VISIBLE = "answer_visible_"
    private const val KEY_PHASE             = "sched_phase_"
    private const val KEY_QA_REMAINING      = "sched_qa_rem_"
    private const val KEY_RESET_TIME_MS     = "sched_reset_ms_"
    private const val QUEUE_SEP             = "\u001F"
    private const val KEY_IS_QA_TURN        = "sched_qa_turn_"

    const val MODE_TRANSPARENT = 0
    const val MODE_TEXT = 1
    const val MODE_IMAGE = 2
    const val PHASE_CYCLE = 0
    const val PHASE_POST  = 1

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

    fun getCurrentAnswer(context: Context, appWidgetId: Int): String? =
        prefs(context).getString(KEY_CURRENT_ANSWER + appWidgetId, null)

    fun setCurrentAnswer(context: Context, appWidgetId: Int, answer: String?) {
        prefs(context).edit().putString(KEY_CURRENT_ANSWER + appWidgetId, answer).apply()
    }

    fun isAnswerVisible(context: Context, appWidgetId: Int): Boolean =
        prefs(context).getBoolean(KEY_ANSWER_VISIBLE + appWidgetId, false)

    fun setAnswerVisible(context: Context, appWidgetId: Int, visible: Boolean) {
        prefs(context).edit().putBoolean(KEY_ANSWER_VISIBLE + appWidgetId, visible).apply()
    }

    fun getRefreshMinutes(context: Context, appWidgetId: Int): Int =
        prefs(context).getInt(KEY_REFRESH_MIN + appWidgetId, DEFAULT_REFRESH_MIN)

    fun setRefreshMinutes(context: Context, appWidgetId: Int, minutes: Int) {
        prefs(context).edit().putInt(KEY_REFRESH_MIN + appWidgetId, minutes.coerceAtLeast(MIN_REFRESH_MIN)).apply()
    }

    // ── Scheduling state ────────────────────────────────────────────

    fun getPhase(context: Context, appWidgetId: Int): Int =
        prefs(context).getInt(KEY_PHASE + appWidgetId, PHASE_CYCLE)

    fun setPhase(context: Context, appWidgetId: Int, phase: Int) {
        prefs(context).edit().putInt(KEY_PHASE + appWidgetId, phase).apply()
    }

    fun getQaRemaining(context: Context, appWidgetId: Int): List<String> {
        val raw = prefs(context).getString(KEY_QA_REMAINING + appWidgetId, "") ?: ""
        return if (raw.isEmpty()) emptyList() else raw.split(QUEUE_SEP)
    }

    fun setQaRemaining(context: Context, appWidgetId: Int, lines: List<String>) {
        prefs(context).edit()
            .putString(KEY_QA_REMAINING + appWidgetId, lines.joinToString(QUEUE_SEP))
            .apply()
    }

    fun getResetTimeMs(context: Context, appWidgetId: Int): Long =
        prefs(context).getLong(KEY_RESET_TIME_MS + appWidgetId, 0L)

    fun setResetTimeMs(context: Context, appWidgetId: Int, ms: Long) {
        prefs(context).edit().putLong(KEY_RESET_TIME_MS + appWidgetId, ms).apply()
    }

    fun isQaTurn(context: Context, appWidgetId: Int): Boolean =
        prefs(context).getBoolean(KEY_IS_QA_TURN + appWidgetId, true)

    fun setIsQaTurn(context: Context, appWidgetId: Int, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_IS_QA_TURN + appWidgetId, value).apply()
    }

    fun remove(context: Context, appWidgetId: Int) {
        prefs(context).edit()
            .remove(KEY_MODE + appWidgetId)
            .remove(KEY_TXT_URI + appWidgetId)
            .remove(KEY_REFRESH_MIN + appWidgetId)
            .remove(KEY_CURRENT_LINE + appWidgetId)
            .remove(KEY_CURRENT_ANSWER + appWidgetId)
            .remove(KEY_ANSWER_VISIBLE + appWidgetId)
            .remove(KEY_PHASE + appWidgetId)
            .remove(KEY_QA_REMAINING + appWidgetId)
            .remove(KEY_RESET_TIME_MS + appWidgetId)
            .remove(KEY_IS_QA_TURN + appWidgetId)
            .apply()
    }
}
