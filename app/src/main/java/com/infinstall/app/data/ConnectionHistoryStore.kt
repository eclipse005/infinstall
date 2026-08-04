package com.infinstall.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

data class HostEntry(
    val host: String,
    val port: Int,
    val label: String = "",
    val lastConnectedAt: Long = System.currentTimeMillis(),
) {
    val display: String
        get() = if (label.isNotBlank()) "$label ($host:$port)" else "$host:$port"

    val endpoint: String get() = "$host:$port"
}

private val Context.dataStore by preferencesDataStore("infinstall_prefs")

class ConnectionHistoryStore(private val context: Context) {
    private val keyHistory = stringPreferencesKey("connection_history_json")

    val history: Flow<List<HostEntry>> = context.dataStore.data.map { prefs ->
        parse(prefs[keyHistory].orEmpty())
    }

    suspend fun rememberSuccess(host: String, port: Int, label: String = "") {
        context.dataStore.edit { prefs ->
            val list = parse(prefs[keyHistory].orEmpty()).toMutableList()
            list.removeAll { it.host == host && it.port == port }
            list.add(
                0,
                HostEntry(
                    host = host.trim(),
                    port = port,
                    label = label,
                    lastConnectedAt = System.currentTimeMillis(),
                ),
            )
            while (list.size > 20) list.removeLast()
            prefs[keyHistory] = serialize(list)
        }
    }

    suspend fun remove(host: String, port: Int) {
        context.dataStore.edit { prefs ->
            val list = parse(prefs[keyHistory].orEmpty()).filterNot {
                it.host == host && it.port == port
            }
            prefs[keyHistory] = serialize(list)
        }
    }

    private fun parse(raw: String): List<HostEntry> {
        if (raw.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        HostEntry(
                            host = o.getString("host"),
                            port = o.getInt("port"),
                            label = o.optString("label", ""),
                            lastConnectedAt = o.optLong("lastConnectedAt", 0L),
                        ),
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun serialize(list: List<HostEntry>): String {
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(
                JSONObject()
                    .put("host", e.host)
                    .put("port", e.port)
                    .put("label", e.label)
                    .put("lastConnectedAt", e.lastConnectedAt),
            )
        }
        return arr.toString()
    }
}
