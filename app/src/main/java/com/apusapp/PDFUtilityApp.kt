package com.apusapp

import android.app.Application
import com.apusapp.utils.VaultManager
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

/**
 * Application class for PDFUtilityApp.
 * Initializes the PDFBox resource loader synchronously to ensure PDF operations
 * triggered via cold-start intents or immediate user actions never throw uninitialized errors.
 * Also cleans up any stale decrypted vault preview files.
 */
class PDFUtilityApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Initialize PDFBox resource loader synchronously (fast, <15ms) to guarantee safety
        try {
            PDFBoxResourceLoader.init(applicationContext)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Clean up any lingering temporary decrypted vault previews from previous sessions
        try {
            VaultManager.clearVaultCache(applicationContext)
            // Clean up old temporary files created by tools from previous sessions
            cacheDir.listFiles { file ->
                file.name.startsWith("wm_") || file.name.startsWith("del_") || file.name.startsWith("rot_") ||
                file.name.startsWith("text_conv_") || file.name.startsWith("import_") || file.name.startsWith("num_in_")
            }?.forEach { it.delete() }
        } catch (_: Exception) {}
    }
}
