package com.calmguard.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class EventEntry(val time: Long, val title: String, val detail: String, val kind: String)

/** Shared by the activity, motion service and SMS result receiver in the app process. */
internal class EventHistory(context: Context) {
    private val prefs = context.getSharedPreferences("calmguard", Context.MODE_PRIVATE)

    fun read(): List<EventEntry> = synchronized(lock) {
        val events = runCatching { JSONArray(prefs.getString(KEY, "[]")) }.getOrElse { JSONArray() }
        List(events.length()) { index ->
            val entry = events.optJSONObject(index) ?: return@List null
            runCatching {
                EventEntry(entry.getLong("time"), entry.getString("title"),
                    entry.getString("detail"), entry.getString("kind"))
            }.getOrNull()
        }.filterNotNull()
    }

    fun add(title: String, detail: String, kind: String, time: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            val events = listOf(EventEntry(time, title, detail, kind)) + read().take(199)
            val array = JSONArray()
            events.forEach {
                array.put(JSONObject().put("time", it.time).put("title", it.title)
                    .put("detail", it.detail).put("kind", it.kind))
            }
            prefs.edit().putString(KEY, array.toString()).apply()
        }
    }

    fun clear() {
        synchronized(lock) { prefs.edit().remove(KEY).apply() }
    }

    companion object {
        const val KEY = "events"
        private val lock = Any()
    }
}
