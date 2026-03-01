package com.bnyro.wallpaper.util

import android.content.Context

object ShuffleQueue {
    private const val PREFS_NAME = "shuffle_queue"
    private const val SEP = "\n"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun loadList(context: Context, key: String): MutableList<String> {
        val raw = prefs(context).getString(key, null) ?: return mutableListOf()
        return raw.split(SEP).filter { it.isNotEmpty() }.toMutableList()
    }

    private fun saveList(context: Context, key: String, list: List<String>) {
        prefs(context).edit().putString(key, list.joinToString(SEP)).apply()
    }

    private fun remainingKey(queue: String) = "remaining_$queue"
    private fun usedKey(queue: String) = "used_$queue"

    fun pickNext(context: Context, queueName: String, currentKeys: Set<String>): String? {
        if (currentKeys.isEmpty()) return null

        val remaining = loadList(context, remainingKey(queueName))
        val used = loadList(context, usedKey(queueName))

        val knownKeys = (remaining + used).toSet()
        val added = currentKeys - knownKeys
        val removedFromRemaining = remaining.filter { it in currentKeys }.toMutableList()

        removedFromRemaining.addAll(added.shuffled())

        if (removedFromRemaining.isEmpty()) {
            removedFromRemaining.addAll(currentKeys.shuffled())
            used.clear()
        }

        val pick = removedFromRemaining.removeFirst()
        used.add(pick)

        saveList(context, remainingKey(queueName), removedFromRemaining)
        saveList(context, usedKey(queueName), used)

        return pick
    }

    fun clear(context: Context, queueName: String) {
        prefs(context).edit()
            .remove(remainingKey(queueName))
            .remove(usedKey(queueName))
            .apply()
    }

    fun removeKey(context: Context, queueName: String, key: String) {
        val remaining = loadList(context, remainingKey(queueName))
        val used = loadList(context, usedKey(queueName))
        remaining.remove(key)
        used.remove(key)
        saveList(context, remainingKey(queueName), remaining)
        saveList(context, usedKey(queueName), used)
    }
}
