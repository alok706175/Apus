package com.apusapp.utils

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Manages the recently opened and recently saved/exported PDF files in the app.
 * Keeps an ordered list prioritizing the most recently accessed/saved files first.
 * Strictly excludes any files located in or opened from Private Vault.
 * Uses robust JSON serialization with backward compatibility for legacy formats.
 */
object RecentFilesManager {
    private const val PREFS_NAME = "recent_files_v2"
    private const val KEY_RECENT_LIST = "recent_paths_with_timestamps"
    private const val MAX_RECENT_ITEMS = 50

    private val _recentFilesFlow = MutableStateFlow<List<PdfFileItem>>(emptyList())
    val recentFilesFlow: StateFlow<List<PdfFileItem>> = _recentFilesFlow.asStateFlow()

    fun init(context: Context) {
        loadRecent(context)
    }

    /**
     * Records a file whenever it is OPENED (viewed in Reader/Main)
     * OR whenever it is SAVED / EXPORTED (via tools, Save As, Compression, Merge, etc.)
     * Excludes any files located in or opened from Private Vault.
     */
    fun recordFile(context: Context, file: File?) {
        if (file == null || !file.exists() || !file.name.endsWith(".pdf", ignoreCase = true)) return
        val appContext = context.applicationContext
        // Strictly prevent any Private Vault file or decrypted preview from appearing in Recent Files
        if (VaultManager.isVaultFile(appContext, file)) return

        try {
            val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val currentRaw = prefs.getString(KEY_RECENT_LIST, "") ?: ""
            val entries = parseEntries(currentRaw).toMutableList()

            // Remove existing entry for the same path and purge any vault paths
            entries.removeAll { it.first.equals(file.absolutePath, ignoreCase = true) || VaultManager.isVaultPath(appContext, it.first) }
            // Add to the top with current timestamp
            entries.add(0, Pair(file.absolutePath, System.currentTimeMillis()))

            val trimmed = entries.take(MAX_RECENT_ITEMS)
            val serialized = serializeEntries(trimmed)
            prefs.edit { putString(KEY_RECENT_LIST, serialized) }

            loadRecent(appContext)
        } catch (_: Exception) {}
    }

    fun removeFile(context: Context, file: File?) {
        if (file == null) return
        val appContext = context.applicationContext
        try {
            val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val currentRaw = prefs.getString(KEY_RECENT_LIST, "") ?: ""
            val entries = parseEntries(currentRaw).toMutableList()
            entries.removeAll { it.first.equals(file.absolutePath, ignoreCase = true) }
            val serialized = serializeEntries(entries)
            prefs.edit { putString(KEY_RECENT_LIST, serialized) }
            loadRecent(appContext)
        } catch (_: Exception) {}
    }

    fun loadRecent(context: Context): List<PdfFileItem> {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentRaw = prefs.getString(KEY_RECENT_LIST, "") ?: ""
        val entries = parseEntries(currentRaw)

        // Fallback to old SharedPreferences if new one is empty
        val list = if (entries.isEmpty()) {
            val oldPrefs = appContext.getSharedPreferences("recent_files", Context.MODE_PRIVATE)
            val oldPaths = oldPrefs.getStringSet("paths", emptySet()) ?: emptySet()
            oldPaths.mapNotNull { p ->
                if (VaultManager.isVaultPath(appContext, p)) null
                else {
                    val f = File(p)
                    if (f.exists() && !VaultManager.isVaultFile(appContext, f)) PdfHelper.getPdfFileItem(f) else null
                }
            }.sortedByDescending { it.dateModified }
        } else {
            entries.mapNotNull { (path, _) ->
                if (VaultManager.isVaultPath(appContext, path)) null
                else {
                    val f = File(path)
                    if (f.exists() && !VaultManager.isVaultFile(appContext, f)) PdfHelper.getPdfFileItem(f) else null
                }
            }
        }

        _recentFilesFlow.value = list
        return list
    }

    internal fun serializeEntries(entries: List<Pair<String, Long>>): String {
        val arr = JSONArray()
        for ((path, time) in entries) {
            val obj = JSONObject()
            obj.put("path", path)
            obj.put("time", time)
            arr.put(obj)
        }
        return arr.toString()
    }

    internal fun parseEntries(raw: String): List<Pair<String, Long>> {
        if (raw.isBlank()) return emptyList()
        val trimmed = raw.trim()
        if (trimmed.startsWith("[")) {
            return try {
                val arr = JSONArray(trimmed)
                val list = mutableListOf<Pair<String, Long>>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val path = obj.optString("path", "")
                    val time = obj.optLong("time", 0L)
                    if (path.isNotBlank()) {
                        list.add(Pair(path, time))
                    }
                }
                list
            } catch (_: Exception) {
                emptyList()
            }
        }

        // Legacy fallback for pre-existing delimited format (e.g. path::timestamp;;path2::timestamp2)
        return raw.split(";;").mapNotNull { item ->
            val parts = item.split("::")
            if (parts.size >= 2) {
                val path = parts[0]
                val timestamp = parts[1].toLongOrNull() ?: 0L
                Pair(path, timestamp)
            } else if (parts.size == 1 && parts[0].isNotBlank()) {
                Pair(parts[0], 0L)
            } else null
        }
    }
}
