package com.apusapp.utils

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class TrashItem(
    val id: String,
    val originalPath: String,
    val originalName: String,
    val trashFilePath: String,
    val deletedTimestamp: Long,
    val sizeBytes: Long
)

/**
 * Manages soft-deleted PDF documents in a Recycle Bin / Trash repository.
 * Allows instant recovery (Restore) and prevents accidental document loss.
 */
object TrashManager {

    private const val PREFS_NAME = "apus_trash_metadata"
    private const val KEY_METADATA = "trash_entries"
    private const val RETENTION_PERIOD_MS = 30L * 24 * 60 * 60 * 1000L // 30 Days Auto-Purge

    private val _trashItemsFlow = MutableStateFlow<List<TrashItem>>(emptyList())
    val trashItemsFlow: StateFlow<List<TrashItem>> = _trashItemsFlow.asStateFlow()

    private fun getTrashDir(context: Context): File {
        val dir = File(context.filesDir, "trash_bin")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    suspend fun init(context: Context) = withContext(Dispatchers.IO) {
        loadMetadata(context)
    }

    private fun loadMetadata(context: Context) {
        val prefs = getPrefs(context)
        val jsonStr = prefs.getString(KEY_METADATA, "[]") ?: "[]"
        val list = mutableListOf<TrashItem>()
        var purgedAny = false
        val now = System.currentTimeMillis()

        runCatching {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val trashPath = obj.getString("trashFilePath")
                val deletedTimestamp = obj.getLong("deletedTimestamp")
                val diskFile = File(trashPath)

                // Automatically purge files older than 30 days to prevent disk space leakage
                if (now - deletedTimestamp > RETENTION_PERIOD_MS) {
                    try { diskFile.delete() } catch (_: Exception) {}
                    purgedAny = true
                    continue
                }

                if (diskFile.exists()) {
                    list.add(
                        TrashItem(
                            id = obj.getString("id"),
                            originalPath = obj.getString("originalPath"),
                            originalName = obj.getString("originalName"),
                            trashFilePath = trashPath,
                            deletedTimestamp = deletedTimestamp,
                            sizeBytes = obj.optLong("sizeBytes", 0L)
                        )
                    )
                } else {
                    purgedAny = true
                }
            }
        }
        val sorted = list.sortedByDescending { it.deletedTimestamp }
        _trashItemsFlow.value = sorted
        if (purgedAny) {
            saveMetadata(context, sorted)
        }
    }

    private fun saveMetadata(context: Context, items: List<TrashItem>) {
        val prefs = getPrefs(context)
        val arr = JSONArray()
        for (item in items) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("originalPath", item.originalPath)
                put("originalName", item.originalName)
                put("trashFilePath", item.trashFilePath)
                put("deletedTimestamp", item.deletedTimestamp)
                put("sizeBytes", item.sizeBytes)
            }
            arr.put(obj)
        }
        prefs.edit().putString(KEY_METADATA, arr.toString()).apply()
        _trashItemsFlow.value = items.sortedByDescending { it.deletedTimestamp }
    }

    suspend fun moveToTrash(context: Context, sourceFile: File): Result<TrashItem> = withContext(Dispatchers.IO) {
        runCatching {
            require(sourceFile.exists()) { "Source file does not exist: ${sourceFile.absolutePath}" }

            val id = "trash_${System.currentTimeMillis()}_${(1000..9999).random()}"
            val trashDir = getTrashDir(context)
            val destFile = File(trashDir, "${id}_${sourceFile.name}")

            // Copy to trash then delete original
            sourceFile.inputStream().use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            val size = destFile.length()
            val origPath = sourceFile.absolutePath
            val origName = sourceFile.name

            // Delete original file
            sourceFile.delete()

            val trashItem = TrashItem(
                id = id,
                originalPath = origPath,
                originalName = origName,
                trashFilePath = destFile.absolutePath,
                deletedTimestamp = System.currentTimeMillis(),
                sizeBytes = size
            )

            loadMetadata(context)
            val updated = _trashItemsFlow.value.toMutableList().apply { add(0, trashItem) }
            saveMetadata(context, updated)

            trashItem
        }
    }

    suspend fun restoreFromTrash(context: Context, item: TrashItem): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val trashFile = File(item.trashFilePath)
            require(trashFile.exists()) { "Trashed file not found on disk" }

            var dest = File(item.originalPath)
            if (dest.exists()) {
                // If a file already exists at original path, create duplicate with _restored
                val parent = dest.parentFile ?: context.filesDir
                dest = File(parent, "Restored_${dest.name}")
            }

            dest.parentFile?.mkdirs()
            trashFile.inputStream().use { input ->
                dest.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            trashFile.delete()

            val current = _trashItemsFlow.value.toMutableList()
            current.removeAll { it.id == item.id }
            saveMetadata(context, current)

            dest
        }
    }

    suspend fun restoreAll(context: Context): Int = withContext(Dispatchers.IO) {
        val current = _trashItemsFlow.value.toList()
        var restoredCount = 0
        for (item in current) {
            val res = restoreFromTrash(context, item)
            if (res.isSuccess) {
                restoredCount++
            }
        }
        restoredCount
    }

    suspend fun deletePermanently(context: Context, item: TrashItem): Boolean = withContext(Dispatchers.IO) {
        val trashFile = File(item.trashFilePath)
        if (trashFile.exists()) trashFile.delete()

        val current = _trashItemsFlow.value.toMutableList()
        current.removeAll { it.id == item.id }
        saveMetadata(context, current)
        true
    }

    suspend fun emptyTrash(context: Context): Int = withContext(Dispatchers.IO) {
        val current = _trashItemsFlow.value
        var count = 0
        for (item in current) {
            val file = File(item.trashFilePath)
            if (file.exists()) {
                file.delete()
                count++
            }
        }
        saveMetadata(context, emptyList())
        count
    }
}

