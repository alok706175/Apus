package com.apusapp.utils

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

enum class SaveLocation(val id: String, val defaultPath: String) {
    APUS_FOLDER("apus_folder", "Documents/Apus"),
    CUSTOM_FOLDER("custom_folder", "Custom Folder");

    companion object {
        fun fromId(id: String): SaveLocation {
            return entries.find { it.id == id } ?: APUS_FOLDER
        }
    }
}

object StorageLocationManager {
    private const val PREFS_NAME = "apus_pdf_storage_prefs"
    private const val KEY_SAVE_LOCATION = "default_save_location"
    private const val KEY_CUSTOM_TREE_URI = "custom_save_tree_uri"
    private const val KEY_CUSTOM_FOLDER_NAME = "custom_save_folder_name"
    private const val KEY_IS_FOLDER_CHOSEN = "user_has_chosen_save_folder"

    private var prefs: SharedPreferences? = null

    private val _currentLocationFlow = MutableStateFlow(SaveLocation.APUS_FOLDER)
    val currentLocationFlow: StateFlow<SaveLocation> = _currentLocationFlow.asStateFlow()

    private val _folderDisplayNameFlow = MutableStateFlow("Documents/Apus")
    val folderDisplayNameFlow: StateFlow<String> = _folderDisplayNameFlow.asStateFlow()

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val savedId = prefs?.getString(KEY_SAVE_LOCATION, SaveLocation.APUS_FOLDER.id) ?: SaveLocation.APUS_FOLDER.id
            val loc = SaveLocation.fromId(savedId)
            _currentLocationFlow.value = loc
            val customName = prefs?.getString(KEY_CUSTOM_FOLDER_NAME, null)
            _folderDisplayNameFlow.value = if (loc == SaveLocation.CUSTOM_FOLDER && !customName.isNullOrBlank()) {
                customName
            } else {
                "Documents/Apus"
            }
        }
    }

    fun isFolderChosen(context: Context): Boolean {
        init(context)
        return prefs?.getBoolean(KEY_IS_FOLDER_CHOSEN, false) ?: false
    }

    fun setFolderChosen(context: Context, chosen: Boolean) {
        init(context)
        prefs?.edit()?.putBoolean(KEY_IS_FOLDER_CHOSEN, chosen)?.apply()
    }

    fun getLocation(context: Context): SaveLocation {
        init(context)
        return _currentLocationFlow.value
    }

    fun getFolderDisplayName(context: Context): String {
        init(context)
        return _folderDisplayNameFlow.value
    }

    fun hasCustomFolder(context: Context): Boolean {
        init(context)
        return !prefs?.getString(KEY_CUSTOM_TREE_URI, null).isNullOrBlank()
    }

    fun getCustomFolderUri(context: Context): Uri? {
        init(context)
        val uriStr = prefs?.getString(KEY_CUSTOM_TREE_URI, null) ?: return null
        return try {
            Uri.parse(uriStr)
        } catch (_: Exception) {
            null
        }
    }

    fun setCustomFolderUri(context: Context, treeUri: Uri) {
        init(context)
        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(treeUri, flags)
        } catch (_: Exception) {}

        val readableName = resolveReadablePath(context, treeUri) ?: "Selected Folder"

        prefs?.edit()
            ?.putString(KEY_SAVE_LOCATION, SaveLocation.CUSTOM_FOLDER.id)
            ?.putString(KEY_CUSTOM_TREE_URI, treeUri.toString())
            ?.putString(KEY_CUSTOM_FOLDER_NAME, readableName)
            ?.putBoolean(KEY_IS_FOLDER_CHOSEN, true)
            ?.apply()

        _currentLocationFlow.value = SaveLocation.CUSTOM_FOLDER
        _folderDisplayNameFlow.value = readableName
    }

    fun setPredefinedApusFolder(context: Context) {
        init(context)
        prefs?.edit()
            ?.putString(KEY_SAVE_LOCATION, SaveLocation.APUS_FOLDER.id)
            ?.putBoolean(KEY_IS_FOLDER_CHOSEN, true)
            ?.apply()

        _currentLocationFlow.value = SaveLocation.APUS_FOLDER
        _folderDisplayNameFlow.value = "Documents/Apus"
    }

    fun setLocation(context: Context, location: SaveLocation) {
        init(context)
        _currentLocationFlow.value = location
        prefs?.edit()
            ?.putString(KEY_SAVE_LOCATION, location.id)
            ?.putBoolean(KEY_IS_FOLDER_CHOSEN, true)
            ?.apply()

        if (location == SaveLocation.APUS_FOLDER) {
            _folderDisplayNameFlow.value = "Documents/Apus"
        } else {
            val customName = prefs?.getString(KEY_CUSTOM_FOLDER_NAME, null)
            _folderDisplayNameFlow.value = customName ?: "Custom Folder"
        }
    }

    private fun resolveReadablePath(context: Context, treeUri: Uri): String? {
        try {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            if (docId != null && docId.contains(":")) {
                val parts = docId.split(":")
                if (parts.size >= 2) {
                    val rel = parts[1].trim('/')
                    return if (rel.isNotBlank()) rel else "Root Storage"
                }
            }
        } catch (_: Exception) {}
        return DocumentFile.fromTreeUri(context, treeUri)?.name
    }

    /**
     * Resolves the real shared public directory on device (e.g. /storage/emulated/0/Documents/Apus).
     * NEVER uses internal app storage (filesDir/cacheDir), so files survive uninstall completely.
     */
    fun getTargetDirectory(context: Context): File {
        init(context)
        if (_currentLocationFlow.value == SaveLocation.CUSTOM_FOLDER) {
            val treeUri = getCustomFolderUri(context)
            if (treeUri != null) {
                try {
                    val docId = DocumentsContract.getTreeDocumentId(treeUri)
                    if (docId != null && docId.startsWith("primary:")) {
                        val relPath = docId.removePrefix("primary:").trim('/')
                        val externalRoot = Environment.getExternalStorageDirectory()
                        val dir = if (relPath.isNotBlank()) File(externalRoot, relPath) else externalRoot
                        if (!dir.exists()) dir.mkdirs()
                        if (dir.exists() && dir.canWrite()) {
                            return dir
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        // Shared external public storage directory (survives app uninstall)
        val publicDocs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val apusDir = File(publicDocs, "Apus")
        if (!apusDir.exists()) {
            apusDir.mkdirs()
        }
        return apusDir
    }

    fun getRelativePath(context: Context): String {
        return "${Environment.DIRECTORY_DOCUMENTS}/Apus"
    }

    /**
     * Helper to save a generated PDF file to the user's selected save location.
     * Guaranteed to persist on public shared storage so files are never deleted on app uninstall.
     */
    fun savePdfToStorage(context: Context, sourceFile: File, outputFileName: String): File {
        init(context)

        // 1. If user selected a custom SAF tree URI, write through SAF DocumentFile
        if (_currentLocationFlow.value == SaveLocation.CUSTOM_FOLDER) {
            val treeUri = getCustomFolderUri(context)
            if (treeUri != null) {
                try {
                    val pickedDir = DocumentFile.fromTreeUri(context, treeUri)
                    if (pickedDir != null && pickedDir.canWrite()) {
                        val baseName = outputFileName.removeSuffix(".pdf")
                        var finalDocName = outputFileName
                        var count = 1
                        while (pickedDir.findFile(finalDocName) != null) {
                            finalDocName = "${baseName}_$count.pdf"
                            count++
                        }

                        val newDoc = pickedDir.createFile("application/pdf", finalDocName)
                        if (newDoc != null) {
                            context.contentResolver.openOutputStream(newDoc.uri)?.use { os ->
                                FileInputStream(sourceFile).use { `is` -> `is`.copyTo(os) }
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        // 2. Android 10+ MediaStore insertion for system-wide indexing and accessibility on shared storage
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val relPath = getRelativePath(context)
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, outputFileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
                }
                val uri = context.contentResolver.insert(MediaStore.Files.getContentUri("external"), contentValues)
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use { os ->
                        FileInputStream(sourceFile).use { `is` -> `is`.copyTo(os) }
                    }
                }
            } catch (_: Exception) {}
        }

        // 3. Direct write to public external storage directory
        val targetDir = getTargetDirectory(context)
        var targetFile = File(targetDir, outputFileName)
        var count = 1
        val baseName = outputFileName.removeSuffix(".pdf")
        while (targetFile.exists()) {
            targetFile = File(targetDir, "${baseName}_$count.pdf")
            count++
        }

        FileInputStream(sourceFile).use { input ->
            FileOutputStream(targetFile).use { output ->
                input.copyTo(output)
            }
        }

        // Automatically register newly saved/exported file into Recent Files
        RecentFilesManager.recordFile(context, targetFile)

        return targetFile
    }
}
